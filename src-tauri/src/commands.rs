use crate::AppState;
use dzr_core::{Paged, Playlist, Resolved, Track, User};
use serde_json::Value;
use std::sync::Arc;
use tauri::{AppHandle, Manager, Runtime};

fn state<R: Runtime>(app: &AppHandle<R>) -> tauri::State<'_, Arc<AppState>> {
    app.state::<Arc<AppState>>()
}

#[tauri::command]
pub async fn deezer_search<R: Runtime>(app: AppHandle<R>, q: String) -> Result<Paged<Track>, String> {
    let s = state(&app);
    s.deezer.search(&q).await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn deezer_user<R: Runtime>(app: AppHandle<R>, id: i64) -> Result<User, String> {
    let s = state(&app);
    s.deezer.user(id).await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn deezer_user_playlists<R: Runtime>(
    app: AppHandle<R>,
    id: i64,
) -> Result<Paged<Playlist>, String> {
    let s = state(&app);
    s.deezer.user_playlists(id).await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn deezer_user_tracks<R: Runtime>(
    app: AppHandle<R>,
    id: i64,
) -> Result<Paged<Track>, String> {
    let s = state(&app);
    s.deezer.user_tracks(id).await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn deezer_user_flow<R: Runtime>(
    app: AppHandle<R>,
    id: i64,
) -> Result<Paged<Track>, String> {
    let s = state(&app);
    s.deezer.user_flow(id).await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn deezer_playlist_tracks<R: Runtime>(
    app: AppHandle<R>,
    id: i64,
) -> Result<Paged<Track>, String> {
    let s = state(&app);
    s.deezer
        .playlist_tracks(id)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn deezer_chart_tracks<R: Runtime>(app: AppHandle<R>) -> Result<Paged<Track>, String> {
    let s = state(&app);
    s.deezer.chart_tracks().await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn resolve_track<R: Runtime>(
    app: AppHandle<R>,
    track_id: i64,
    artist: String,
    title: String,
    duration: Option<u64>,
    force: Option<bool>,
) -> Result<Resolved, String> {
    let s = state(&app);
    if force.unwrap_or(false) {
        s.resolver.invalidate(track_id);
    }
    s.resolver
        .resolve(track_id, &artist, &title, duration)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub fn resolver_invalidate<R: Runtime>(app: AppHandle<R>, track_id: i64) {
    let s = state(&app);
    s.resolver.invalidate(track_id);
}

#[tauri::command]
pub async fn ytdlp_version<R: Runtime>(app: AppHandle<R>) -> Result<String, String> {
    let _ = state(&app);
    let path = match std::env::var("DZR_YTDLP") {
        Ok(p) => std::path::PathBuf::from(p),
        Err(_) => std::path::PathBuf::from("yt-dlp"),
    };
    let out = std::process::Command::new(path)
        .arg("--version")
        .output()
        .map_err(|e| e.to_string())?;
    if !out.status.success() {
        return Err(String::from_utf8_lossy(&out.stderr).to_string());
    }
    Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
}

#[allow(dead_code)]
fn _unused() -> Value {
    Value::Null
}
