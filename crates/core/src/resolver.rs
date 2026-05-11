use crate::ytsearch::YouTubeSearch;
use anyhow::{anyhow, Context, Result};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::process::Command;

fn normalize(s: &str) -> String {
    s.chars()
        .map(|c| if c.is_alphanumeric() { c.to_ascii_lowercase() } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn tokenize(s: &str) -> Vec<String> {
    let stop: HashSet<&'static str> = [
        "a", "an", "the", "and", "or", "of", "in", "on", "at", "to", "for", "with", "by",
        "feat", "ft", "featuring", "remix", "remastered", "official", "video", "audio",
    ]
    .iter()
    .copied()
    .collect();
    normalize(s)
        .split_whitespace()
        .filter(|t| t.len() >= 3 && !stop.contains(t))
        .map(|t| t.to_string())
        .collect()
}

const URL_TTL: Duration = Duration::from_secs(60 * 60 * 4);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Resolved {
    pub url: String,
    pub video_id: String,
    pub duration: Option<u64>,
    pub title: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct DiskCacheEntry {
    video_id: String,
    duration: Option<u64>,
    title: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MatchInfo {
    pub video_id: String,
    pub duration: Option<u64>,
    pub title: Option<String>,
}

#[derive(Clone)]
pub struct Resolver {
    youtube: Arc<YouTubeSearch>,
    ytdlp_binary: PathBuf,
    url_cache: Arc<DashMap<i64, (Resolved, Instant)>>,
    match_cache: Arc<DashMap<i64, DiskCacheEntry>>,
    match_cache_path: Option<PathBuf>,
}

impl Resolver {
    pub fn new(ytdlp_binary: impl Into<PathBuf>) -> Result<Self> {
        let youtube = YouTubeSearch::new().context("youtube search init")?;
        Ok(Self {
            youtube: Arc::new(youtube),
            ytdlp_binary: ytdlp_binary.into(),
            url_cache: Arc::new(DashMap::new()),
            match_cache: Arc::new(DashMap::new()),
            match_cache_path: None,
        })
    }

    pub fn with_match_cache(mut self, path: PathBuf) -> Self {
        if let Ok(s) = std::fs::read_to_string(&path) {
            if let Ok(m) =
                serde_json::from_str::<std::collections::HashMap<i64, DiskCacheEntry>>(&s)
            {
                for (k, v) in m {
                    self.match_cache.insert(k, v);
                }
            }
        }
        self.match_cache_path = Some(path);
        self
    }

    pub fn invalidate(&self, track_id: i64) {
        self.url_cache.remove(&track_id);
        self.match_cache.remove(&track_id);
        self.persist_match_cache();
    }

    fn cached_url(&self, track_id: i64) -> Option<Resolved> {
        let entry = self.url_cache.get(&track_id)?;
        let (r, t) = entry.value();
        if t.elapsed() > URL_TTL {
            return None;
        }
        Some(r.clone())
    }

    fn cached_match(&self, track_id: i64) -> Option<DiskCacheEntry> {
        self.match_cache.get(&track_id).map(|e| e.value().clone())
    }

    fn persist_match_cache(&self) {
        let Some(path) = &self.match_cache_path else {
            return;
        };
        let map: std::collections::HashMap<i64, DiskCacheEntry> = self
            .match_cache
            .iter()
            .map(|e| (*e.key(), e.value().clone()))
            .collect();
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).ok();
        }
        if let Ok(s) = serde_json::to_string(&map) {
            std::fs::write(path, s).ok();
        }
    }

    /// Match a Deezer track to a YouTube video_id via rusty_ytdl search.
    /// Fast (~1s) — no Python subprocess.
    pub async fn match_track(
        &self,
        track_id: i64,
        artist: &str,
        title: &str,
        target_duration: Option<u64>,
    ) -> Result<MatchInfo> {
        if let Some(m) = self.cached_match(track_id) {
            return Ok(MatchInfo {
                video_id: m.video_id,
                duration: m.duration,
                title: m.title,
            });
        }
        let query = format!("{} - {}", artist, title.replace('"', ""));
        let results = self
            .youtube
            .search(&query, 5)
            .await
            .map_err(|e| anyhow!("youtube search: {e}"))?;

        let mut videos: Vec<(String, Option<u64>, String, Option<String>)> = results
            .into_iter()
            .map(|v| (v.id, v.duration_sec, v.title, v.channel))
            .collect();
        if videos.is_empty() {
            return Err(anyhow!("no search results for `{query}`"));
        }

        let artist_tokens = tokenize(artist);
        let title_tokens = tokenize(title);
        let artist_norm = normalize(artist);

        // filter: must have artist+title tokens AND channel must be an official source
        //   ("<Artist> - Topic", "VEVO", or channel name == artist name)
        videos.retain(|(_id, _d, vtitle, ch)| {
            let hay = normalize(&format!(
                "{} {}",
                vtitle,
                ch.as_deref().unwrap_or("")
            ));
            let artist_ok = artist_tokens.is_empty()
                || artist_tokens.iter().all(|t| hay.contains(t.as_str()));
            let title_ok = title_tokens.is_empty()
                || title_tokens.iter().all(|t| hay.contains(t.as_str()));
            if !(artist_ok && title_ok) {
                return false;
            }
            let ch_norm = normalize(ch.as_deref().unwrap_or(""));
            let official = ch_norm.ends_with(" topic")
                || ch_norm.contains("vevo")
                || (!artist_norm.is_empty() && ch_norm.contains(&artist_norm));
            official
        });
        if videos.is_empty() {
            return Err(anyhow!("no official match for `{query}`"));
        }

        if let Some(target) = target_duration {
            videos.sort_by_key(|(_id, d, _t, _c)| {
                d.map(|d| (target as i64 - d as i64).abs())
                    .unwrap_or(i64::MAX)
            });
        }
        let (video_id, duration, vtitle, _ch) = videos.into_iter().next().unwrap();
        let vtitle = Some(vtitle);

        if let (Some(target), Some(actual)) = (target_duration, duration) {
            let delta = (target as i64 - actual as i64).abs() as u64;
            let tol = std::cmp::max(5, target / 20);
            if delta > tol {
                return Err(anyhow!(
                    "no close duration match (delta {}s > tol {}s) for `{query}`",
                    delta,
                    tol
                ));
            }
        }

        self.match_cache.insert(
            track_id,
            DiskCacheEntry {
                video_id: video_id.clone(),
                duration,
                title: vtitle.clone(),
            },
        );
        self.persist_match_cache();
        Ok(MatchInfo {
            video_id,
            duration,
            title: vtitle,
        })
    }

    /// Resolve to a direct googlevideo URL (for webview consumption).
    /// Uses yt-dlp -g — rusty_ytdl can't decipher audio URLs reliably.
    pub async fn resolve(
        &self,
        track_id: i64,
        artist: &str,
        title: &str,
        target_duration: Option<u64>,
    ) -> Result<Resolved> {
        if let Some(r) = self.cached_url(track_id) {
            return Ok(r);
        }
        let m = self
            .match_track(track_id, artist, title, target_duration)
            .await?;
        let url = self.ytdlp_get_url(&m.video_id).await?;
        let resolved = Resolved {
            url,
            video_id: m.video_id,
            duration: m.duration,
            title: m.title,
        };
        self.url_cache
            .insert(track_id, (resolved.clone(), Instant::now()));
        Ok(resolved)
    }

    /// Download audio to `dest_stem.{ext}` via yt-dlp.
    /// Returns actual path with extension.
    pub async fn download_audio(&self, video_id: &str, dest_stem: &Path) -> Result<PathBuf> {
        if let Some(parent) = dest_stem.parent() {
            std::fs::create_dir_all(parent).ok();
        }
        let template = format!("{}.%(ext)s", dest_stem.display());
        let out = Command::new(&self.ytdlp_binary)
            .args([
                "--quiet",
                "--no-warnings",
                "--no-playlist",
                "--ignore-config",
                "-f",
                "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio",
                "-o",
                &template,
                &format!("https://www.youtube.com/watch?v={video_id}"),
            ])
            .output()
            .await
            .context("yt-dlp spawn")?;
        if !out.status.success() {
            return Err(anyhow!(
                "yt-dlp failed: {}",
                String::from_utf8_lossy(&out.stderr)
            ));
        }
        // discover the actual file
        let stem_name = dest_stem
            .file_name()
            .and_then(|s| s.to_str())
            .ok_or_else(|| anyhow!("bad dest_stem"))?
            .to_string();
        let parent = dest_stem
            .parent()
            .ok_or_else(|| anyhow!("no parent"))?;
        for entry in std::fs::read_dir(parent)? {
            let entry = entry?;
            if entry
                .path()
                .file_stem()
                .and_then(|s| s.to_str())
                .map(|s| s == stem_name)
                .unwrap_or(false)
            {
                return Ok(entry.path());
            }
        }
        Err(anyhow!("downloaded file not found"))
    }

    async fn ytdlp_get_url(&self, video_id: &str) -> Result<String> {
        let url = format!("https://www.youtube.com/watch?v={video_id}");
        let out = Command::new(&self.ytdlp_binary)
            .args([
                "--quiet",
                "--no-warnings",
                "--no-playlist",
                "--ignore-config",
                "-f",
                "bestaudio[ext=m4a]/bestaudio",
                "-g",
                &url,
            ])
            .output()
            .await
            .context("yt-dlp -g")?;
        if !out.status.success() {
            return Err(anyhow!(
                "yt-dlp -g failed: {}",
                String::from_utf8_lossy(&out.stderr)
            ));
        }
        String::from_utf8_lossy(&out.stdout)
            .lines()
            .next()
            .map(str::to_string)
            .ok_or_else(|| anyhow!("yt-dlp -g empty"))
    }
}
