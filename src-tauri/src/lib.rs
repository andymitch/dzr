mod commands;

use dzr_core::{DeezerClient, Resolver};
use std::path::PathBuf;
use std::sync::Arc;
use tauri::Manager;

pub struct AppState {
    pub deezer: DeezerClient,
    pub resolver: Resolver,
}

fn locate_ytdlp() -> PathBuf {
    if let Ok(p) = std::env::var("DZR_YTDLP") {
        return PathBuf::from(p);
    }
    // when bundled as sidecar via tauri, Contents/MacOS/yt-dlp
    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            let candidate = parent.join("yt-dlp");
            if candidate.is_file() {
                return candidate;
            }
        }
    }
    if let Ok(path) = std::env::var("PATH") {
        for dir in std::env::split_paths(&path) {
            let c = dir.join("yt-dlp");
            if c.is_file() {
                return c;
            }
        }
    }
    PathBuf::from("yt-dlp")
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_store::Builder::new().build())
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let ytdlp = locate_ytdlp();
            let cache_dir = directories::ProjectDirs::from("com", "klear", "dzr")
                .map(|d| d.cache_dir().to_path_buf());
            let resolver = Resolver::new(ytdlp).expect("init resolver");
            let resolver = match cache_dir {
                Some(p) => resolver.with_match_cache(p.join("yt_match.json")),
                None => resolver,
            };
            let state = AppState {
                deezer: DeezerClient::new(),
                resolver,
            };
            app.manage(Arc::new(state));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::deezer_search,
            commands::deezer_user,
            commands::deezer_user_playlists,
            commands::deezer_user_tracks,
            commands::deezer_user_flow,
            commands::deezer_playlist_tracks,
            commands::deezer_chart_tracks,
            commands::resolve_track,
            commands::resolver_invalidate,
            commands::ytdlp_version,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
