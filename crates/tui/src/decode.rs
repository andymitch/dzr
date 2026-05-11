use anyhow::{anyhow, Context, Result};
use rodio::Source;
use std::collections::VecDeque;
use std::fs::File;
use std::time::Duration;
use symphonia::core::audio::{AudioBufferRef, Signal};
use symphonia::core::codecs::{CodecParameters, CODEC_TYPE_OPUS};
use symphonia::core::errors::Error as SymphError;
use symphonia::core::formats::{FormatOptions, FormatReader};
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

const OPUS_SAMPLE_RATE: u32 = 48_000;
const OPUS_MAX_FRAME_SAMPLES: usize = 5760;

pub struct AudioSource {
    format: Box<dyn FormatReader>,
    backend: Backend,
    track_id: u32,
    sample_rate: u32,
    channels: u16,
    queue: VecDeque<f32>,
    finished: bool,
}

enum Backend {
    Symphonia(Box<dyn symphonia::core::codecs::Decoder>),
    Opus(opus::Decoder, u16, Vec<f32>),
}

impl AudioSource {
    pub fn open(path: &std::path::Path) -> Result<Self> {
        let file = File::open(path).with_context(|| format!("open {}", path.display()))?;
        let mss = MediaSourceStream::new(Box::new(file), Default::default());
        let mut hint = Hint::new();
        if let Some(ext) = path.extension().and_then(|s| s.to_str()) {
            hint.with_extension(ext);
        }
        let probe = symphonia::default::get_probe();
        let probed = probe
            .format(
                &hint,
                mss,
                &FormatOptions {
                    enable_gapless: true,
                    ..Default::default()
                },
                &MetadataOptions::default(),
            )
            .context("probe format")?;
        let format = probed.format;
        let track = format
            .tracks()
            .iter()
            .find(|t| t.codec_params.codec != symphonia::core::codecs::CODEC_TYPE_NULL)
            .context("no decodable track")?;
        let track_id = track.id;
        let codec_params = track.codec_params.clone();
        let sample_rate = codec_params.sample_rate.unwrap_or(OPUS_SAMPLE_RATE);
        let channels = codec_params
            .channels
            .map(|c| c.count() as u16)
            .unwrap_or(2);
        let backend = build_backend(&codec_params, channels)?;
        Ok(Self {
            format,
            backend,
            track_id,
            sample_rate: if matches!(backend_kind(&codec_params), BackendKind::Opus) {
                OPUS_SAMPLE_RATE
            } else {
                sample_rate
            },
            channels,
            queue: VecDeque::with_capacity(8192),
            finished: false,
        })
    }

    fn fill(&mut self) -> bool {
        if self.finished {
            return false;
        }
        loop {
            let packet = match self.format.next_packet() {
                Ok(p) => p,
                Err(SymphError::IoError(e))
                    if e.kind() == std::io::ErrorKind::UnexpectedEof =>
                {
                    self.finished = true;
                    return false;
                }
                Err(SymphError::ResetRequired) => {
                    self.finished = true;
                    return false;
                }
                Err(e) => {
                    tracing::warn!("next_packet: {e}");
                    continue;
                }
            };
            if packet.track_id() != self.track_id {
                continue;
            }
            match &mut self.backend {
                Backend::Symphonia(dec) => match dec.decode(&packet) {
                    Ok(buf) => {
                        write_buffer_to_queue(buf, &mut self.queue);
                        return true;
                    }
                    Err(SymphError::DecodeError(e)) => {
                        tracing::warn!("decode error: {e}");
                        continue;
                    }
                    Err(e) => {
                        tracing::warn!("decode: {e}");
                        self.finished = true;
                        return false;
                    }
                },
                Backend::Opus(dec, channels, scratch) => {
                    if scratch.len() < OPUS_MAX_FRAME_SAMPLES * (*channels as usize) {
                        scratch.resize(OPUS_MAX_FRAME_SAMPLES * (*channels as usize), 0.0);
                    }
                    match dec.decode_float(&packet.data, scratch.as_mut_slice(), false) {
                        Ok(samples) => {
                            let total = samples * (*channels as usize);
                            self.queue.extend(scratch[..total].iter().copied());
                            return true;
                        }
                        Err(e) => {
                            tracing::warn!("opus decode: {e}");
                            continue;
                        }
                    }
                }
            }
        }
    }
}

fn write_buffer_to_queue(buf: AudioBufferRef<'_>, q: &mut VecDeque<f32>) {
    let spec = *buf.spec();
    let frames = buf.frames();
    let chans = spec.channels.count();
    macro_rules! handle {
        ($variant:ident, $convert:expr) => {{
            let inner = match buf {
                AudioBufferRef::$variant(b) => b,
                _ => unreachable!(),
            };
            for frame in 0..frames {
                for ch in 0..chans {
                    let s = inner.chan(ch)[frame];
                    q.push_back($convert(s));
                }
            }
        }};
    }
    match &buf {
        AudioBufferRef::F32(_) => handle!(F32, |s: f32| s),
        AudioBufferRef::F64(_) => handle!(F64, |s: f64| s as f32),
        AudioBufferRef::S8(_) => handle!(S8, |s: i8| s as f32 / i8::MAX as f32),
        AudioBufferRef::S16(_) => handle!(S16, |s: i16| s as f32 / i16::MAX as f32),
        AudioBufferRef::S24(_) => handle!(S24, |s: symphonia::core::sample::i24| {
            s.inner() as f32 / 8_388_607.0
        }),
        AudioBufferRef::S32(_) => handle!(S32, |s: i32| s as f32 / i32::MAX as f32),
        AudioBufferRef::U8(_) => handle!(U8, |s: u8| (s as f32 - 128.0) / 128.0),
        AudioBufferRef::U16(_) => handle!(U16, |s: u16| (s as f32 - 32768.0) / 32768.0),
        AudioBufferRef::U24(_) => handle!(U24, |s: symphonia::core::sample::u24| {
            (s.inner() as f32 - 8_388_608.0) / 8_388_608.0
        }),
        AudioBufferRef::U32(_) => handle!(U32, |s: u32| (s as f32 - 2_147_483_648.0)
            / 2_147_483_648.0),
    }
    let _ = spec;
}

enum BackendKind {
    Opus,
    Other,
}

fn backend_kind(p: &CodecParameters) -> BackendKind {
    if p.codec == CODEC_TYPE_OPUS {
        BackendKind::Opus
    } else {
        BackendKind::Other
    }
}

fn build_backend(p: &CodecParameters, channels: u16) -> Result<Backend> {
    if p.codec == CODEC_TYPE_OPUS {
        let ch = match channels {
            1 => opus::Channels::Mono,
            _ => opus::Channels::Stereo,
        };
        let dec = opus::Decoder::new(OPUS_SAMPLE_RATE, ch).map_err(|e| anyhow!("opus init: {e}"))?;
        Ok(Backend::Opus(dec, channels, Vec::new()))
    } else {
        let dec = symphonia::default::get_codecs()
            .make(p, &symphonia::core::codecs::DecoderOptions::default())
            .context("make decoder")?;
        Ok(Backend::Symphonia(dec))
    }
}

impl Iterator for AudioSource {
    type Item = f32;
    fn next(&mut self) -> Option<f32> {
        loop {
            if let Some(s) = self.queue.pop_front() {
                return Some(s);
            }
            if !self.fill() {
                return None;
            }
        }
    }
}

impl Source for AudioSource {
    fn current_span_len(&self) -> Option<usize> {
        None
    }
    fn channels(&self) -> rodio::ChannelCount {
        self.channels
    }
    fn sample_rate(&self) -> rodio::SampleRate {
        self.sample_rate
    }
    fn total_duration(&self) -> Option<Duration> {
        None
    }
}
