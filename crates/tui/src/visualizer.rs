use parking_lot::Mutex;
use rodio::Source;
use rustfft::num_complex::Complex;
use rustfft::{Fft, FftPlanner};
use std::collections::VecDeque;
use std::sync::Arc;
use std::time::Duration;

const RING_SAMPLES: usize = 2048;
const FFT_SIZE: usize = 1024;

#[derive(Default)]
pub struct SampleRing {
    inner: Mutex<VecDeque<f32>>,
}

impl SampleRing {
    pub fn new() -> Arc<Self> {
        let mut v = VecDeque::with_capacity(RING_SAMPLES);
        v.resize(RING_SAMPLES, 0.0);
        Arc::new(Self {
            inner: Mutex::new(v),
        })
    }

    pub fn push(&self, sample: f32) {
        let mut g = self.inner.lock();
        if g.len() >= RING_SAMPLES {
            g.pop_front();
        }
        g.push_back(sample);
    }

    pub fn snapshot(&self) -> Vec<f32> {
        let g = self.inner.lock();
        let mut out = Vec::with_capacity(FFT_SIZE);
        let n = g.len();
        let start = n.saturating_sub(FFT_SIZE);
        for i in start..n {
            out.push(g[i]);
        }
        while out.len() < FFT_SIZE {
            out.push(0.0);
        }
        out
    }

    pub fn clear(&self) {
        let mut g = self.inner.lock();
        for s in g.iter_mut() {
            *s = 0.0;
        }
    }
}

pub struct TappedSource<S> {
    inner: S,
    ring: Arc<SampleRing>,
    channels: u16,
    counter: u16,
}

impl<S: Source<Item = f32>> TappedSource<S> {
    pub fn new(inner: S, ring: Arc<SampleRing>) -> Self {
        let channels = inner.channels();
        Self {
            inner,
            ring,
            channels,
            counter: 0,
        }
    }
}

impl<S: Source<Item = f32>> Iterator for TappedSource<S> {
    type Item = f32;
    fn next(&mut self) -> Option<f32> {
        let s = self.inner.next()?;
        if self.channels <= 1 || self.counter == 0 {
            self.ring.push(s);
        }
        self.counter = (self.counter + 1) % self.channels.max(1);
        Some(s)
    }
}

impl<S: Source<Item = f32>> Source for TappedSource<S> {
    fn current_span_len(&self) -> Option<usize> {
        self.inner.current_span_len()
    }
    fn channels(&self) -> rodio::ChannelCount {
        self.inner.channels()
    }
    fn sample_rate(&self) -> rodio::SampleRate {
        self.inner.sample_rate()
    }
    fn total_duration(&self) -> Option<Duration> {
        self.inner.total_duration()
    }
}

pub struct FftBars {
    fft: Arc<dyn Fft<f32>>,
    scratch: Vec<Complex<f32>>,
    buf: Vec<Complex<f32>>,
    window: Vec<f32>,
    bands: Vec<f32>,
    n_bands: usize,
    smoothed: Vec<f32>,
    edges: Vec<(usize, usize)>,
}

impl FftBars {
    pub fn new(n_bands: usize) -> Self {
        let mut planner = FftPlanner::<f32>::new();
        let fft = planner.plan_fft_forward(FFT_SIZE);
        let bins = FFT_SIZE / 2;
        let min_bin = 2.0f32;
        // cap upper freq ~ 3 kHz @ 48k sample rate
        let max_bin = 64.0f32.min(bins as f32);
        let edges: Vec<(usize, usize)> = (0..n_bands)
            .map(|b| {
                let lo = (min_bin * (max_bin / min_bin).powf(b as f32 / n_bands as f32)) as usize;
                let hi = (min_bin
                    * (max_bin / min_bin).powf((b as f32 + 1.0) / n_bands as f32))
                    .min(max_bin) as usize;
                (lo.min(bins - 1), hi.max(lo + 1).min(bins as usize))
            })
            .collect();
        let window: Vec<f32> = (0..FFT_SIZE)
            .map(|i| {
                0.5 - 0.5
                    * ((2.0 * std::f32::consts::PI * i as f32) / (FFT_SIZE as f32 - 1.0)).cos()
            })
            .collect();
        Self {
            fft,
            scratch: Vec::new(),
            buf: vec![Complex::new(0.0, 0.0); FFT_SIZE],
            window,
            bands: vec![0.0; n_bands],
            n_bands,
            smoothed: vec![0.0; n_bands],
            edges,
        }
    }

    pub fn n_bands(&self) -> usize {
        self.n_bands
    }

    pub fn compute(&mut self, samples: &[f32]) -> &[f32] {
        let n = samples.len().min(FFT_SIZE);
        for i in 0..n {
            self.buf[i] = Complex::new(samples[i] * self.window[i], 0.0);
        }
        for i in n..FFT_SIZE {
            self.buf[i] = Complex::new(0.0, 0.0);
        }
        if self.scratch.is_empty() {
            self.scratch.resize(
                self.fft.get_inplace_scratch_len(),
                Complex::new(0.0, 0.0),
            );
        }
        self.fft
            .process_with_scratch(&mut self.buf, &mut self.scratch);

        for b in 0..self.n_bands {
            let (lo, hi) = self.edges[b];
            let mut peak = 0.0f32;
            for v in &self.buf[lo..hi] {
                let mag2 = v.re * v.re + v.im * v.im;
                if mag2 > peak {
                    peak = mag2;
                }
            }
            let mag = peak.sqrt();
            let db = 20.0 * (mag / FFT_SIZE as f32 + 1e-6).log10();
            let norm = ((db + 50.0) / 50.0).clamp(0.0, 1.0);
            self.bands[b] = norm;
        }
        for i in 0..self.n_bands {
            let prev = self.smoothed[i];
            let now = self.bands[i];
            // fast attack, fast decay for responsiveness
            self.smoothed[i] = if now > prev {
                now
            } else {
                prev * 0.45 + now * 0.55
            };
        }
        &self.smoothed
    }
}
