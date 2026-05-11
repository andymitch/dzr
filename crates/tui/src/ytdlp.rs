use anyhow::{anyhow, Context, Result};
use std::path::PathBuf;

#[cfg(all(target_os = "macos", target_arch = "aarch64"))]
const EMBEDDED: &[u8] = include_bytes!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../src-tauri/binaries/yt-dlp-aarch64-apple-darwin"
));

#[cfg(not(all(target_os = "macos", target_arch = "aarch64")))]
const EMBEDDED: &[u8] = &[];

pub fn ensure_binary() -> Result<PathBuf> {
    if let Ok(p) = std::env::var("DZR_YTDLP") {
        return Ok(PathBuf::from(p));
    }
    if let Some(p) = which_path("yt-dlp") {
        return Ok(p);
    }
    if EMBEDDED.is_empty() {
        return Err(anyhow!(
            "yt-dlp not found and no embedded binary for this platform; install via `brew install yt-dlp` or set DZR_YTDLP"
        ));
    }
    let cache_dir = dirs::cache_dir()
        .ok_or_else(|| anyhow!("no cache dir"))?
        .join("dzr");
    std::fs::create_dir_all(&cache_dir).context("create cache dir")?;
    let path = cache_dir.join("yt-dlp");
    let needs_write = match std::fs::metadata(&path) {
        Ok(meta) => meta.len() as usize != EMBEDDED.len(),
        Err(_) => true,
    };
    if needs_write {
        std::fs::write(&path, EMBEDDED).context("write embedded yt-dlp")?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755))
                .context("chmod yt-dlp")?;
        }
    }
    Ok(path)
}

fn which_path(name: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path)
        .map(|d| d.join(name))
        .find(|p| p.is_file())
}
