use anyhow::{anyhow, Context, Result};
use reqwest::Client;
use serde::{Deserialize, Deserializer, Serialize};

const API: &str = "https://api.deezer.com";

fn null_to_empty<'de, D>(d: D) -> Result<String, D::Error>
where
    D: Deserializer<'de>,
{
    Ok(Option::<String>::deserialize(d)?.unwrap_or_default())
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ArtistRef {
    pub id: i64,
    pub name: String,
    #[serde(default, deserialize_with = "null_to_empty")]
    pub picture_small: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AlbumRef {
    pub id: i64,
    pub title: String,
    #[serde(default, deserialize_with = "null_to_empty")]
    pub cover_medium: String,
    #[serde(default, deserialize_with = "null_to_empty")]
    pub cover_big: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Track {
    pub id: i64,
    pub title: String,
    pub duration: u64,
    #[serde(default, deserialize_with = "null_to_empty")]
    pub preview: String,
    pub artist: ArtistRef,
    pub album: AlbumRef,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Playlist {
    pub id: i64,
    pub title: String,
    pub nb_tracks: u64,
    #[serde(default, deserialize_with = "null_to_empty")]
    pub picture_medium: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct User {
    pub id: i64,
    pub name: String,
    #[serde(default, deserialize_with = "null_to_empty")]
    pub picture_medium: String,
    #[serde(default, deserialize_with = "null_to_empty")]
    pub link: String,
}

#[derive(Debug, Deserialize, Serialize)]
pub struct Paged<T> {
    pub data: Vec<T>,
    #[serde(default)]
    pub total: u64,
    #[serde(default)]
    pub next: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ApiErrorEnvelope {
    error: Option<ApiError>,
}

#[derive(Debug, Deserialize)]
struct ApiError {
    #[serde(rename = "type")]
    typ: String,
    message: String,
}

#[derive(Clone)]
pub struct DeezerClient {
    http: Client,
}

impl DeezerClient {
    pub fn new() -> Self {
        Self {
            http: Client::builder()
                .user_agent("dzr/0.1")
                .build()
                .expect("reqwest client"),
        }
    }

    async fn get<T: serde::de::DeserializeOwned>(&self, path: &str) -> Result<T> {
        let url = format!("{API}{path}");
        let res = self
            .http
            .get(&url)
            .send()
            .await
            .with_context(|| format!("GET {url}"))?;
        let text = res.text().await.context("read body")?;
        if let Ok(env) = serde_json::from_str::<ApiErrorEnvelope>(&text) {
            if let Some(err) = env.error {
                return Err(anyhow!("{}: {}", err.typ, err.message));
            }
        }
        serde_json::from_str::<T>(&text)
            .with_context(|| format!("decode response body: {}", truncate(&text, 200)))
    }

    pub async fn search(&self, q: &str) -> Result<Paged<Track>> {
        self.get(&format!("/search?q={}&limit=50", urlencode(q))).await
    }

    pub async fn user(&self, id: i64) -> Result<User> {
        self.get(&format!("/user/{id}")).await
    }

    pub async fn user_playlists(&self, id: i64) -> Result<Paged<Playlist>> {
        self.get(&format!("/user/{id}/playlists?limit=100")).await
    }

    pub async fn user_tracks(&self, id: i64) -> Result<Paged<Track>> {
        self.get(&format!("/user/{id}/tracks?limit=200")).await
    }

    pub async fn user_flow(&self, id: i64) -> Result<Paged<Track>> {
        self.get(&format!("/user/{id}/flow?limit=40")).await
    }

    pub async fn playlist_tracks(&self, id: i64) -> Result<Paged<Track>> {
        self.get(&format!("/playlist/{id}/tracks?limit=500")).await
    }

    pub async fn chart_tracks(&self) -> Result<Paged<Track>> {
        self.get("/chart/0/tracks?limit=100").await
    }
}

impl Default for DeezerClient {
    fn default() -> Self {
        Self::new()
    }
}

fn urlencode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                (b as char).to_string()
            }
            _ => format!("%{:02X}", b),
        })
        .collect()
}

fn truncate(s: &str, n: usize) -> String {
    if s.len() <= n {
        s.to_string()
    } else {
        format!("{}…", &s[..n])
    }
}

pub fn parse_user_id(input: &str) -> Option<i64> {
    let t = input.trim();
    if t.chars().all(|c| c.is_ascii_digit()) {
        return t.parse().ok();
    }
    let needle = "/profile/";
    let idx = t.find(needle)?;
    let rest = &t[idx + needle.len()..];
    let end = rest.find(|c: char| !c.is_ascii_digit()).unwrap_or(rest.len());
    rest[..end].parse().ok()
}
