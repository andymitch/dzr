use crate::audio::AudioPlayer;
use crate::visualizer::FftBars;
use crate::Msg;
use dzr_core::{DeezerClient, MatchInfo, Playlist, Resolver, Track, User};
use ratatui_image::picker::Picker;
use ratatui_image::protocol::StatefulProtocol;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::mpsc::UnboundedSender;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Pane {
    Sidebar,
    Tracks,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResStatus {
    Idle,
    Resolving,
    Resolved,
    Failed,
}

pub struct App {
    pub user_id: i64,
    pub deezer: DeezerClient,
    pub resolver: Resolver,
    pub tx: UnboundedSender<Msg>,
    pub picker: Option<Picker>,

    pub profile: Option<User>,
    pub playlists: Vec<Playlist>,
    pub liked: Vec<Track>,
    pub active_title: String,
    pub active_tracks: Vec<Track>,

    pub sidebar_idx: usize,
    pub tracks_idx: usize,
    pub focus: Pane,
    pub search_buf: Option<String>,
    pub help_open: bool,

    pub status_msg: Option<String>,

    pub resolutions: HashMap<i64, (ResStatus, Option<String>, Option<String>)>,
    pub video_ids: HashMap<i64, String>,
    pub audio: Arc<AudioPlayer>,
    pub queue: Vec<Track>,
    pub queue_idx: Option<usize>,
    pub shuffle: bool,

    pub art_protocol: Option<StatefulProtocol>,
    pub art_track_id: Option<i64>,
    pub fetched_art: std::collections::HashSet<i64>,

    pub sidebar_rect: ratatui::layout::Rect,
    pub tracks_rect: ratatui::layout::Rect,
    pub sidebar_offset: usize,
    pub tracks_offset: usize,

    pub fft: FftBars,
}

impl App {
    pub fn new(
        user_id: i64,
        deezer: DeezerClient,
        resolver: Resolver,
        tx: UnboundedSender<Msg>,
    ) -> Self {
        let picker = Picker::from_query_stdio().ok();
        let audio = Arc::new(
            AudioPlayer::new(tx.clone(), resolver.clone()).unwrap_or_else(|e| {
                tracing::error!("audio init failed: {e}");
                AudioPlayer::silent(tx.clone(), resolver.clone())
            }),
        );
        Self {
            user_id,
            deezer,
            resolver,
            tx,
            picker,
            profile: None,
            playlists: Vec::new(),
            liked: Vec::new(),
            active_title: "Charts".into(),
            active_tracks: Vec::new(),
            sidebar_idx: 0,
            tracks_idx: 0,
            focus: Pane::Sidebar,
            search_buf: None,
            help_open: false,
            status_msg: None,
            resolutions: HashMap::new(),
            video_ids: HashMap::new(),
            audio,
            queue: Vec::new(),
            queue_idx: None,
            shuffle: false,
            art_protocol: None,
            art_track_id: None,
            fetched_art: Default::default(),
            sidebar_rect: ratatui::layout::Rect::default(),
            tracks_rect: ratatui::layout::Rect::default(),
            sidebar_offset: 0,
            tracks_offset: 0,
            fft: FftBars::new(16),
        }
    }

    pub fn focus(&mut self, p: Pane) {
        self.focus = p;
    }

    pub async fn mouse_click(&mut self, x: u16, y: u16) {
        let pt = ratatui::layout::Position { x, y };
        if rect_contains(self.sidebar_rect, pt) {
            self.focus = Pane::Sidebar;
            let row = y.saturating_sub(self.sidebar_rect.y + 1) as usize;
            let idx = self.sidebar_offset + row;
            if idx < self.sidebar_entries().len() {
                self.sidebar_idx = idx;
                self.activate_sidebar().await;
            }
        } else if rect_contains(self.tracks_rect, pt) {
            self.focus = Pane::Tracks;
            let row = y.saturating_sub(self.tracks_rect.y + 1) as usize;
            let idx = self.tracks_offset + row;
            if idx < self.active_tracks.len() {
                self.tracks_idx = idx;
                self.play_at(idx).await;
            }
        }
    }

    pub fn mouse_scroll(&mut self, x: u16, y: u16, up: bool) {
        let pt = ratatui::layout::Position { x, y };
        let pane = if rect_contains(self.sidebar_rect, pt) {
            Pane::Sidebar
        } else if rect_contains(self.tracks_rect, pt) {
            Pane::Tracks
        } else {
            return;
        };
        let prev = self.focus;
        self.focus = pane;
        for _ in 0..3 {
            if up {
                self.move_up();
            } else {
                self.move_down();
            }
        }
        self.focus = prev;
    }

    pub fn cycle_pane(&mut self) {
        self.focus = match self.focus {
            Pane::Sidebar => Pane::Tracks,
            Pane::Tracks => Pane::Sidebar,
        };
    }

    pub fn toggle_help(&mut self) {
        self.help_open = !self.help_open;
    }

    pub fn cancel_overlay(&mut self) {
        if self.help_open {
            self.help_open = false;
        } else if self.search_buf.is_some() {
            self.search_buf = None;
        }
    }

    pub fn start_search(&mut self) {
        self.search_buf = Some(String::new());
    }

    pub fn is_search_active(&self) -> bool {
        self.search_buf.is_some()
    }

    pub fn search_input(&mut self, c: char) {
        if let Some(b) = self.search_buf.as_mut() {
            b.push(c);
        }
    }

    pub fn search_backspace(&mut self) {
        if let Some(b) = self.search_buf.as_mut() {
            b.pop();
        }
    }

    pub fn move_up(&mut self) {
        match self.focus {
            Pane::Sidebar => {
                if self.sidebar_idx > 0 {
                    self.sidebar_idx -= 1;
                }
            }
            Pane::Tracks => {
                if self.tracks_idx > 0 {
                    self.tracks_idx -= 1;
                }
            }
        }
    }

    pub fn move_down(&mut self) {
        match self.focus {
            Pane::Sidebar => {
                if self.sidebar_idx + 1 < self.sidebar_entries().len() {
                    self.sidebar_idx += 1;
                }
            }
            Pane::Tracks => {
                if self.tracks_idx + 1 < self.active_tracks.len() {
                    self.tracks_idx += 1;
                }
            }
        }
    }

    pub fn page_up(&mut self) {
        for _ in 0..10 {
            self.move_up();
        }
    }

    pub fn page_down(&mut self) {
        for _ in 0..10 {
            self.move_down();
        }
    }

    pub fn go_top(&mut self) {
        match self.focus {
            Pane::Sidebar => self.sidebar_idx = 0,
            Pane::Tracks => self.tracks_idx = 0,
        }
    }

    pub fn go_bottom(&mut self) {
        match self.focus {
            Pane::Sidebar => {
                let n = self.sidebar_entries().len();
                self.sidebar_idx = n.saturating_sub(1);
            }
            Pane::Tracks => {
                let n = self.active_tracks.len();
                self.tracks_idx = n.saturating_sub(1);
            }
        }
    }

    pub fn sidebar_entries(&self) -> Vec<SidebarEntry> {
        let mut entries = vec![
            SidebarEntry::Section("Browse".into()),
            SidebarEntry::Action(SidebarAction::Charts, "Charts".into()),
            SidebarEntry::Action(SidebarAction::Flow, "Flow".into()),
            SidebarEntry::Action(SidebarAction::Liked, format!("Liked ({})", self.liked.len())),
            SidebarEntry::Section("Playlists".into()),
        ];
        for p in &self.playlists {
            entries.push(SidebarEntry::Playlist(p.id, p.title.clone(), p.nb_tracks));
        }
        entries
    }

    pub async fn activate(&mut self) {
        if let Some(buf) = self.search_buf.take() {
            self.run_search(buf).await;
            return;
        }
        match self.focus {
            Pane::Sidebar => self.activate_sidebar().await,
            Pane::Tracks => self.play_at(self.tracks_idx).await,
        }
    }

    pub async fn activate_sidebar(&mut self) {
        let entries = self.sidebar_entries();
        let Some(entry) = entries.get(self.sidebar_idx) else {
            return;
        };
        match entry.clone() {
            SidebarEntry::Section(_) => {}
            SidebarEntry::Action(action, _) => match action {
                SidebarAction::Charts => self.fetch_charts(),
                SidebarAction::Flow => self.fetch_flow(),
                SidebarAction::Liked => self.show_liked(),
            },
            SidebarEntry::Playlist(id, title, _) => self.fetch_playlist(id, title),
        }
    }

    pub fn fetch_charts_if_empty(&mut self) {
        if self.active_tracks.is_empty() {
            self.fetch_charts();
        }
    }

    fn fetch_charts(&self) {
        let tx = self.tx.clone();
        let d = self.deezer.clone();
        tokio::spawn(async move {
            match d.chart_tracks().await {
                Ok(p) => {
                    let _ = tx.send(Msg::Tracks {
                        title: "Charts".into(),
                        tracks: p.data,
                    });
                }
                Err(e) => {
                    let _ = tx.send(Msg::ListError(e.to_string()));
                }
            }
        });
    }

    fn fetch_flow(&self) {
        let tx = self.tx.clone();
        let d = self.deezer.clone();
        let id = self.user_id;
        tokio::spawn(async move {
            match d.user_flow(id).await {
                Ok(p) => {
                    let _ = tx.send(Msg::Tracks {
                        title: "Flow".into(),
                        tracks: p.data,
                    });
                }
                Err(e) => {
                    let _ = tx.send(Msg::ListError(e.to_string()));
                }
            }
        });
    }

    fn show_liked(&mut self) {
        self.active_title = "Liked".into();
        self.active_tracks = self.liked.clone();
        self.tracks_idx = 0;
        self.focus = Pane::Tracks;
        self.prefetch_visible();
    }

    fn fetch_playlist(&self, id: i64, title: String) {
        let tx = self.tx.clone();
        let d = self.deezer.clone();
        tokio::spawn(async move {
            match d.playlist_tracks(id).await {
                Ok(p) => {
                    let _ = tx.send(Msg::Tracks { title, tracks: p.data });
                }
                Err(e) => {
                    let _ = tx.send(Msg::ListError(e.to_string()));
                }
            }
        });
    }

    async fn run_search(&self, q: String) {
        let tx = self.tx.clone();
        let d = self.deezer.clone();
        tokio::spawn(async move {
            match d.search(&q).await {
                Ok(p) => {
                    let _ = tx.send(Msg::Tracks {
                        title: format!("Search: {q}"),
                        tracks: p.data,
                    });
                }
                Err(e) => {
                    let _ = tx.send(Msg::ListError(e.to_string()));
                }
            }
        });
    }

    pub fn tick(&mut self) {}

    pub async fn handle_msg(&mut self, msg: Msg) {
        match msg {
            Msg::Bootstrapped { user, playlists, liked } => {
                self.profile = user;
                self.playlists = playlists;
                self.liked = liked;
            }
            Msg::BootstrapError(e) => {
                self.status_msg = Some(format!("Bootstrap: {e}"));
            }
            Msg::Tracks { title, tracks } => {
                self.active_title = title;
                self.active_tracks = tracks;
                self.tracks_idx = 0;
                self.focus = Pane::Tracks;
                self.prefetch_visible();
            }
            Msg::ListError(e) => {
                self.status_msg = Some(e);
            }
            Msg::ResolveOk { track_id, video_id } => {
                self.resolutions
                    .insert(track_id, (ResStatus::Resolved, None, None));
                self.video_ids.insert(track_id, video_id.clone());
                if let Some(qi) = self.queue_idx {
                    if let Some(t) = self.queue.get(qi) {
                        if t.id == track_id {
                            if let Err(e) = self.audio.play_url(&video_id) {
                                self.status_msg = Some(format!("audio: {e}"));
                            }
                        }
                    }
                }
            }
            Msg::ResolveErr { track_id, err } => {
                self.resolutions
                    .insert(track_id, (ResStatus::Failed, None, Some(err.clone())));
            }
            Msg::AlbumArt { track_id, bytes } => {
                if self.art_track_id == Some(track_id) {
                    if let Some(picker) = &mut self.picker {
                        match image::load_from_memory(&bytes) {
                            Ok(img) => {
                                self.art_protocol = Some(picker.new_resize_protocol(img));
                            }
                            Err(e) => tracing::warn!("decode art: {e}"),
                        }
                    }
                }
            }
            Msg::PlaybackTick => {}
            Msg::PlaybackEnded => {
                self.next_track().await;
            }
        }
    }

    fn prefetch_visible(&mut self) {
        let count = self.active_tracks.len().min(40);
        for i in 0..count {
            let t = self.active_tracks[i].clone();
            self.start_resolve(t);
        }
    }

    fn start_resolve(&mut self, t: Track) {
        if matches!(
            self.resolutions.get(&t.id).map(|x| x.0),
            Some(ResStatus::Resolved) | Some(ResStatus::Resolving)
        ) {
            return;
        }
        self.resolutions.insert(t.id, (ResStatus::Resolving, None, None));
        let resolver = self.resolver.clone();
        let tx = self.tx.clone();
        tokio::spawn(async move {
            match resolver
                .match_track(t.id, &t.artist.name, &t.title, Some(t.duration))
                .await
            {
                Ok(MatchInfo { video_id, .. }) => {
                    let _ = tx.send(Msg::ResolveOk {
                        track_id: t.id,
                        video_id,
                    });
                }
                Err(e) => {
                    let _ = tx.send(Msg::ResolveErr {
                        track_id: t.id,
                        err: e.to_string(),
                    });
                }
            }
        });
    }

    pub async fn play_at(&mut self, idx: usize) {
        if idx >= self.active_tracks.len() {
            return;
        }
        self.queue = self.active_tracks.clone();
        self.queue_idx = Some(idx);
        self.load_current();
    }

    pub async fn next_track(&mut self) {
        let Some(idx) = self.queue_idx else { return };
        let next = if self.shuffle && self.queue.len() > 1 {
            let mut n = pseudo_rand(self.queue.len());
            if n == idx {
                n = (n + 1) % self.queue.len();
            }
            n
        } else {
            idx + 1
        };
        if next < self.queue.len() {
            self.queue_idx = Some(next);
            self.load_current();
        }
    }

    pub async fn prev_track(&mut self) {
        let Some(idx) = self.queue_idx else { return };
        if idx > 0 {
            self.queue_idx = Some(idx - 1);
            self.load_current();
        }
    }

    pub fn toggle_pause(&self) {
        self.audio.toggle_pause();
    }

    pub fn toggle_shuffle(&mut self) {
        self.shuffle = !self.shuffle;
    }

    pub async fn shuffle_play(&mut self) {
        let mut a = self.active_tracks.clone();
        let n = a.len();
        for i in (1..n).rev() {
            let j = pseudo_rand(i + 1);
            a.swap(i, j);
        }
        self.queue = a;
        self.queue_idx = Some(0);
        self.load_current();
    }

    pub fn vol_up(&self) {
        self.audio.adjust_volume(0.1);
    }
    pub fn vol_down(&self) {
        self.audio.adjust_volume(-0.1);
    }

    fn load_current(&mut self) {
        self.audio.stop();
        let Some(idx) = self.queue_idx else { return };
        let Some(t) = self.queue.get(idx).cloned() else { return };
        self.art_track_id = Some(t.id);
        self.art_protocol = None;
        self.fetch_album_art(&t);
        match (self.resolutions.get(&t.id).map(|x| x.0), self.video_ids.get(&t.id).cloned()) {
            (Some(ResStatus::Resolved), Some(video_id)) => {
                if let Err(e) = self.audio.play_url(&video_id) {
                    self.status_msg = Some(format!("audio: {e}"));
                }
            }
            _ => self.start_resolve(t),
        }
    }

    fn fetch_album_art(&mut self, t: &Track) {
        let url = if !t.album.cover_big.is_empty() {
            t.album.cover_big.clone()
        } else {
            t.album.cover_medium.clone()
        };
        if url.is_empty() {
            return;
        }
        if !self.fetched_art.insert(t.id) {
            return;
        }
        let tx = self.tx.clone();
        let track_id = t.id;
        tokio::spawn(async move {
            match reqwest::get(&url).await {
                Ok(resp) => match resp.bytes().await {
                    Ok(b) => {
                        let _ = tx.send(Msg::AlbumArt {
                            track_id,
                            bytes: b.to_vec(),
                        });
                    }
                    Err(e) => tracing::warn!("art bytes: {e}"),
                },
                Err(e) => tracing::warn!("art fetch: {e}"),
            }
        });
    }

    pub fn current_track(&self) -> Option<&Track> {
        self.queue_idx.and_then(|i| self.queue.get(i))
    }
}

#[derive(Debug, Clone)]
pub enum SidebarEntry {
    Section(String),
    Action(SidebarAction, String),
    Playlist(i64, String, u64),
}

#[derive(Debug, Clone, Copy)]
pub enum SidebarAction {
    Charts,
    Flow,
    Liked,
}

fn rect_contains(r: ratatui::layout::Rect, p: ratatui::layout::Position) -> bool {
    p.x >= r.x && p.x < r.x.saturating_add(r.width) && p.y >= r.y && p.y < r.y.saturating_add(r.height)
}

fn pseudo_rand(n: usize) -> usize {
    use std::time::{SystemTime, UNIX_EPOCH};
    let seed = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.subsec_nanos() as usize)
        .unwrap_or(0);
    seed % n.max(1)
}
