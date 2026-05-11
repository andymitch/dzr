use anyhow::{Context, Result};
use dzr_core::{DeezerClient, Resolver};
use dzr_tui::decode::AudioSource;
use rodio::{OutputStreamBuilder, Sink};
use std::path::PathBuf;
use std::time::Duration;

#[tokio::main(flavor = "multi_thread", worker_threads = 4)]
async fn main() -> Result<()> {
    eprintln!("=== dzr audio smoke test ===");

    let ytdlp = std::env::var("DZR_YTDLP")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("yt-dlp"));
    let deezer = DeezerClient::new();
    let resolver = Resolver::new(ytdlp)?;

    eprintln!("fetching test track from Deezer charts...");
    let charts = deezer.chart_tracks().await?;
    let track = charts
        .data
        .into_iter()
        .next()
        .context("empty charts response")?;
    eprintln!(
        "track: {} - {} ({}s, id={})",
        track.artist.name, track.title, track.duration, track.id
    );

    eprintln!("matching via rusty_ytdl...");
    let t0 = std::time::Instant::now();
    let m = resolver
        .match_track(track.id, &track.artist.name, &track.title, Some(track.duration))
        .await?;
    eprintln!("matched in {:?}: video_id={}", t0.elapsed(), m.video_id);

    let cache_dir = std::env::temp_dir();
    let dest_stem = cache_dir.join(format!("dzr-smoke-{}", m.video_id));
    let _ = std::fs::remove_file(dest_stem.with_extension("m4a"));
    let _ = std::fs::remove_file(dest_stem.with_extension("webm"));
    eprintln!("downloading audio...");
    let t1 = std::time::Instant::now();
    let path = resolver.download_audio(&m.video_id, &dest_stem).await?;
    eprintln!(
        "downloaded {} ({} bytes) in {:?}",
        path.display(),
        std::fs::metadata(&path)?.len(),
        t1.elapsed()
    );

    eprintln!("opening output stream...");
    let stream = OutputStreamBuilder::open_default_stream().context("output stream")?;
    let sink = Sink::connect_new(stream.mixer());
    sink.set_volume(0.4);

    eprintln!("constructing AudioSource from {}...", path.display());
    let t2 = std::time::Instant::now();
    let decoder = AudioSource::open(&path).context("decoder")?;
    eprintln!("decoder ok in {:?}", t2.elapsed());

    sink.append(decoder);
    eprintln!("appended. playing 6s...");
    for i in 1..=6 {
        std::thread::sleep(Duration::from_secs(1));
        eprintln!(
            "  t={}s  empty={}  paused={}",
            i,
            sink.empty(),
            sink.is_paused()
        );
        if sink.empty() {
            eprintln!("  sink empty — playback failed");
            break;
        }
    }
    eprintln!("done");
    Ok(())
}
