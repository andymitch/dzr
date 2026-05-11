use crate::decode::AudioSource;
use crate::visualizer::{SampleRing, TappedSource};
use crate::Msg;
use anyhow::{anyhow, Context, Result};
use dzr_core::Resolver;
use rodio::mixer::Mixer;
use rodio::{OutputStream, OutputStreamBuilder, Sink};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc::UnboundedSender;

pub struct AudioPlayer {
    _stream: Option<OutputStream>,
    mixer: Option<Mixer>,
    state: Arc<Mutex<State>>,
    tx: UnboundedSender<Msg>,
    cache_dir: PathBuf,
    resolver: Resolver,
    ring: Arc<SampleRing>,
}

struct State {
    sink: Option<Arc<Sink>>,
    volume: f32,
    generation: u64,
    loading: bool,
}

impl AudioPlayer {
    pub fn new(tx: UnboundedSender<Msg>, resolver: Resolver) -> Result<Self> {
        let stream = OutputStreamBuilder::open_default_stream().context("default output stream")?;
        let mixer = stream.mixer().clone();
        let cache_dir = dirs::cache_dir()
            .ok_or_else(|| anyhow!("no cache dir"))?
            .join("dzr/audio");
        std::fs::create_dir_all(&cache_dir).ok();
        Ok(Self {
            _stream: Some(stream),
            mixer: Some(mixer),
            state: Arc::new(Mutex::new(State {
                sink: None,
                volume: 0.7,
                generation: 0,
                loading: false,
            })),
            tx,
            cache_dir,
            resolver,
            ring: SampleRing::new(),
        })
    }

    pub fn silent(tx: UnboundedSender<Msg>, resolver: Resolver) -> Self {
        Self {
            _stream: None,
            mixer: None,
            state: Arc::new(Mutex::new(State {
                sink: None,
                volume: 0.0,
                generation: 0,
                loading: false,
            })),
            tx,
            cache_dir: std::env::temp_dir(),
            resolver,
            ring: SampleRing::new(),
        }
    }

    pub fn ring(&self) -> Arc<SampleRing> {
        self.ring.clone()
    }

    pub fn play_url(&self, video_id: &str) -> Result<()> {
        let cache_dir = self.cache_dir.clone();
        let video_id = video_id.to_string();
        let resolver = self.resolver.clone();
        let tx = self.tx.clone();
        let state = self.state.clone();
        let ring = self.ring.clone();
        ring.clear();

        let (gen_id, mixer) = {
            let mut s = state.lock().unwrap();
            s.generation = s.generation.wrapping_add(1);
            s.loading = true;
            if let Some(sink) = s.sink.take() {
                sink.stop();
            }
            let mixer = s
                ._mixer_clone(&self.mixer)
                .ok_or_else(|| anyhow!("audio output unavailable"))?;
            (s.generation, mixer)
        };

        tokio::spawn(async move {
            let cache_path = match find_or_download(&resolver, &cache_dir, &video_id).await {
                Ok(p) => p,
                Err(e) => {
                    tracing::error!("download: {e}");
                    let mut s = state.lock().unwrap();
                    if s.generation == gen_id {
                        s.loading = false;
                    }
                    return;
                }
            };
            let decoder = match AudioSource::open(&cache_path) {
                Ok(d) => d,
                Err(e) => {
                    tracing::error!("decoder: {e}");
                    let mut s = state.lock().unwrap();
                    if s.generation == gen_id {
                        s.loading = false;
                    }
                    return;
                }
            };
            let sink = Arc::new(Sink::connect_new(&mixer));
            {
                let mut s = state.lock().unwrap();
                if s.generation != gen_id {
                    return;
                }
                sink.set_volume(s.volume);
                s.sink = Some(sink.clone());
                s.loading = false;
            }
            sink.append(TappedSource::new(decoder, ring));
            sink.sleep_until_end();
            let still_current = state.lock().unwrap().generation == gen_id;
            if still_current {
                let _ = tx.send(Msg::PlaybackEnded);
            }
        });
        Ok(())
    }

    pub fn toggle_pause(&self) {
        let s = self.state.lock().unwrap();
        if let Some(sink) = &s.sink {
            if sink.is_paused() {
                sink.play();
            } else {
                sink.pause();
            }
        }
    }

    pub fn is_loading(&self) -> bool {
        self.state.lock().unwrap().loading
    }

    pub fn stop(&self) {
        let mut s = self.state.lock().unwrap();
        s.generation = s.generation.wrapping_add(1);
        s.loading = false;
        if let Some(sink) = s.sink.take() {
            sink.stop();
        }
    }

    pub fn adjust_volume(&self, delta: f32) {
        let mut s = self.state.lock().unwrap();
        s.volume = (s.volume + delta).clamp(0.0, 1.5);
        let v = s.volume;
        if let Some(sink) = &s.sink {
            sink.set_volume(v);
        }
    }

    pub fn volume(&self) -> f32 {
        self.state.lock().unwrap().volume
    }

    pub fn is_paused(&self) -> bool {
        self.state
            .lock()
            .unwrap()
            .sink
            .as_ref()
            .map(|s| s.is_paused())
            .unwrap_or(true)
    }

    pub fn is_playing(&self) -> bool {
        self.state
            .lock()
            .unwrap()
            .sink
            .as_ref()
            .map(|s| !s.is_paused() && !s.empty())
            .unwrap_or(false)
    }
}

impl State {
    fn _mixer_clone(&self, m: &Option<Mixer>) -> Option<Mixer> {
        m.clone()
    }
}

async fn find_or_download(
    resolver: &Resolver,
    cache_dir: &Path,
    video_id: &str,
) -> Result<PathBuf> {
    if let Some(p) = find_existing(cache_dir, video_id) {
        return Ok(p);
    }
    let dest_stem = cache_dir.join(video_id);
    resolver.download_audio(video_id, &dest_stem).await
}

fn find_existing(cache_dir: &Path, video_id: &str) -> Option<PathBuf> {
    let entries = std::fs::read_dir(cache_dir).ok()?;
    for e in entries.flatten() {
        if let Some(stem) = e.path().file_stem().and_then(|s| s.to_str()) {
            if stem == video_id {
                return Some(e.path());
            }
        }
    }
    None
}
