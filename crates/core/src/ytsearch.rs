use anyhow::{anyhow, Context, Result};
use reqwest::Client;
use serde::Deserialize;
use serde_json::{json, Value};

const INNERTUBE_KEY: &str = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
const CLIENT_VERSION: &str = "2.20230331.00.00";
const SEARCH_URL: &str = "https://www.youtube.com/youtubei/v1/search";
const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
// EgIQAQ%3D%3D filters by Type=Video
const VIDEO_FILTER: &str = "EgIQAQ%3D%3D";

#[derive(Debug, Clone, Deserialize)]
pub struct VideoResult {
    pub id: String,
    pub title: String,
    pub duration_sec: Option<u64>,
    pub channel: Option<String>,
}

#[derive(Clone)]
pub struct YouTubeSearch {
    client: Client,
}

impl YouTubeSearch {
    pub fn new() -> Result<Self> {
        let client = Client::builder()
            .user_agent(USER_AGENT)
            .build()
            .context("reqwest client")?;
        Ok(Self { client })
    }

    pub async fn search(&self, query: &str, limit: usize) -> Result<Vec<VideoResult>> {
        let body = json!({
            "context": {
                "client": {
                    "clientName": "WEB",
                    "clientVersion": CLIENT_VERSION,
                    "hl": "en",
                    "gl": "US",
                },
            },
            "query": query,
            "params": VIDEO_FILTER,
        });

        let resp = self
            .client
            .post(SEARCH_URL)
            .query(&[("key", INNERTUBE_KEY), ("prettyPrint", "false")])
            .header("Content-Type", "application/json")
            .json(&body)
            .send()
            .await
            .context("innertube search post")?;
        let status = resp.status();
        let json: Value = resp.json().await.context("decode search json")?;
        if !status.is_success() {
            return Err(anyhow!("search status {status}: {json}"));
        }
        Ok(extract_videos(&json, limit))
    }
}

fn extract_videos(root: &Value, limit: usize) -> Vec<VideoResult> {
    let contents = root
        .pointer(
            "/contents/twoColumnSearchResultsRenderer/primaryContents/sectionListRenderer/contents",
        )
        .and_then(|v| v.as_array());
    let Some(sections) = contents else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for section in sections {
        let items = section
            .pointer("/itemSectionRenderer/contents")
            .and_then(|v| v.as_array());
        let Some(items) = items else { continue };
        for item in items {
            let Some(vr) = item.get("videoRenderer") else {
                continue;
            };
            if let Some(v) = parse_video_renderer(vr) {
                out.push(v);
                if out.len() >= limit {
                    return out;
                }
            }
        }
    }
    out
}

fn parse_video_renderer(vr: &Value) -> Option<VideoResult> {
    let id = vr.get("videoId")?.as_str()?.to_string();
    let title = text_from_runs(vr.get("title"))?;
    let duration_sec = vr
        .get("lengthText")
        .and_then(|l| l.get("simpleText"))
        .and_then(|v| v.as_str())
        .and_then(parse_duration);
    let channel = vr
        .get("ownerText")
        .and_then(|o| text_from_runs(Some(o)));
    Some(VideoResult {
        id,
        title,
        duration_sec,
        channel,
    })
}

fn text_from_runs(v: Option<&Value>) -> Option<String> {
    let v = v?;
    if let Some(simple) = v.get("simpleText").and_then(|s| s.as_str()) {
        return Some(simple.to_string());
    }
    let runs = v.get("runs")?.as_array()?;
    let mut out = String::new();
    for r in runs {
        if let Some(t) = r.get("text").and_then(|t| t.as_str()) {
            out.push_str(t);
        }
    }
    if out.is_empty() {
        None
    } else {
        Some(out)
    }
}

fn parse_duration(s: &str) -> Option<u64> {
    let parts: Vec<&str> = s.split(':').collect();
    let nums: Result<Vec<u64>, _> = parts.iter().map(|p| p.parse::<u64>()).collect();
    let nums = nums.ok()?;
    Some(match nums.len() {
        1 => nums[0],
        2 => nums[0] * 60 + nums[1],
        3 => nums[0] * 3600 + nums[1] * 60 + nums[2],
        _ => return None,
    })
}
