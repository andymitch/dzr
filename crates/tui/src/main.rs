mod app;
mod audio;
mod decode;
mod ui;
mod visualizer;
mod ytdlp;

use anyhow::{Context, Result};
use app::{App, Pane};
use crossterm::{
    event::{
        self, DisableMouseCapture, EnableMouseCapture, Event, KeyCode, KeyEventKind, KeyModifiers,
        MouseButton, MouseEventKind,
    },
    execute,
    terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen},
};
use dzr_core::{DeezerClient, Resolver};
use ratatui::{backend::CrosstermBackend, Terminal};
use std::io::{stdout, Stdout};
use std::time::Duration;
use tokio::sync::mpsc;

#[derive(Debug)]
pub enum Msg {
    Bootstrapped {
        user: Option<dzr_core::User>,
        playlists: Vec<dzr_core::Playlist>,
        liked: Vec<dzr_core::Track>,
    },
    BootstrapError(String),
    Tracks {
        title: String,
        tracks: Vec<dzr_core::Track>,
    },
    ListError(String),
    ResolveOk {
        track_id: i64,
        video_id: String,
    },
    ResolveErr {
        track_id: i64,
        err: String,
    },
    AlbumArt {
        track_id: i64,
        bytes: Vec<u8>,
    },
    PlaybackTick,
    PlaybackEnded,
}

#[tokio::main(flavor = "multi_thread", worker_threads = 4)]
async fn main() -> Result<()> {
    let log_path = directories::ProjectDirs::from("com", "klear", "dzr")
        .map(|d| d.data_local_dir().join("dzr-tui.log"));
    if let Some(p) = log_path {
        if let Some(parent) = p.parent() {
            std::fs::create_dir_all(parent).ok();
        }
        if let Ok(f) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&p)
        {
            tracing_subscriber::fmt()
                .with_writer(std::sync::Mutex::new(f))
                .with_env_filter(
                    tracing_subscriber::EnvFilter::try_from_default_env()
                        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
                )
                .init();
        }
    }

    let user_id = read_user_id()?;

    let deezer = DeezerClient::new();
    let ytdlp_path = ytdlp::ensure_binary().context("ensure yt-dlp")?;
    let match_cache_path = directories::ProjectDirs::from("com", "klear", "dzr")
        .map(|d| d.cache_dir().join("yt_match.json"));
    let resolver = Resolver::new(ytdlp_path).context("init resolver")?;
    let resolver = match match_cache_path {
        Some(p) => resolver.with_match_cache(p),
        None => resolver,
    };

    let (tx, mut rx) = mpsc::unbounded_channel::<Msg>();

    {
        let tx = tx.clone();
        let d = deezer.clone();
        tokio::spawn(async move {
            match async {
                let user = d.user(user_id).await.ok();
                let playlists = d.user_playlists(user_id).await.map(|p| p.data).unwrap_or_default();
                let liked = d.user_tracks(user_id).await.map(|p| p.data).unwrap_or_default();
                Ok::<_, anyhow::Error>((user, playlists, liked))
            }
            .await
            {
                Ok((user, playlists, liked)) => {
                    let _ = tx.send(Msg::Bootstrapped { user, playlists, liked });
                }
                Err(e) => {
                    let _ = tx.send(Msg::BootstrapError(e.to_string()));
                }
            }
        });
    }

    let mut terminal = setup_terminal()?;
    let mut app = App::new(user_id, deezer.clone(), resolver.clone(), tx.clone());

    app.fetch_charts_if_empty();

    let result = run(&mut terminal, &mut app, &mut rx).await;

    restore_terminal(&mut terminal)?;
    if let Err(e) = result {
        eprintln!("error: {e:?}");
    }
    Ok(())
}

async fn run(
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    app: &mut App,
    rx: &mut mpsc::UnboundedReceiver<Msg>,
) -> Result<()> {
    let mut last_tick = std::time::Instant::now();
    let tick_rate = Duration::from_millis(50);
    loop {
        terminal.draw(|f| ui::draw(f, app))?;
        let timeout = tick_rate
            .checked_sub(last_tick.elapsed())
            .unwrap_or_else(|| Duration::from_millis(0));
        if event::poll(timeout)? {
            let evt = event::read()?;
            if let Event::Mouse(m) = evt {
                match m.kind {
                    MouseEventKind::Down(MouseButton::Left) => app.mouse_click(m.column, m.row).await,
                    MouseEventKind::ScrollUp => app.mouse_scroll(m.column, m.row, true),
                    MouseEventKind::ScrollDown => app.mouse_scroll(m.column, m.row, false),
                    _ => {}
                }
                continue;
            }
            if let Event::Key(key) = evt {
                if key.kind != KeyEventKind::Press {
                    continue;
                }
                match (key.code, key.modifiers) {
                    (KeyCode::Char('q'), _) | (KeyCode::Char('c'), KeyModifiers::CONTROL) => {
                        break;
                    }
                    (KeyCode::Char('?'), _) => app.toggle_help(),
                    (KeyCode::Tab, _) => app.cycle_pane(),
                    (KeyCode::Left, _) => app.focus(app::Pane::Sidebar),
                    (KeyCode::Right, _) => app.focus(app::Pane::Tracks),
                    (KeyCode::Char('h'), _) => app.focus(app::Pane::Sidebar),
                    (KeyCode::Char('l'), _) => app.focus(app::Pane::Tracks),
                    (KeyCode::Char('1'), _) => app.focus(Pane::Sidebar),
                    (KeyCode::Char('2'), _) => app.focus(Pane::Tracks),
                    (KeyCode::Char('/'), _) => app.start_search(),
                    (KeyCode::Esc, _) => app.cancel_overlay(),
                    (KeyCode::Up, _) | (KeyCode::Char('k'), _) => app.move_up(),
                    (KeyCode::Down, _) | (KeyCode::Char('j'), _) => app.move_down(),
                    (KeyCode::PageUp, _) => app.page_up(),
                    (KeyCode::PageDown, _) => app.page_down(),
                    (KeyCode::Home, _) | (KeyCode::Char('g'), _) => app.go_top(),
                    (KeyCode::End, _) | (KeyCode::Char('G'), _) => app.go_bottom(),
                    (KeyCode::Enter, _) => app.activate().await,
                    (KeyCode::Char(' '), _) => app.toggle_pause(),
                    (KeyCode::Char('n'), m) if !m.contains(KeyModifiers::CONTROL) => app.next_track().await,
                    (KeyCode::Char('p'), m) if !m.contains(KeyModifiers::CONTROL) => app.prev_track().await,
                    (KeyCode::Char('s'), _) => app.toggle_shuffle(),
                    (KeyCode::Char('S'), _) => app.shuffle_play().await,
                    (KeyCode::Char('+'), _) | (KeyCode::Char('='), _) => app.vol_up(),
                    (KeyCode::Char('-'), _) | (KeyCode::Char('_'), _) => app.vol_down(),
                    (KeyCode::Char(c), _) if app.is_search_active() => app.search_input(c),
                    (KeyCode::Backspace, _) if app.is_search_active() => app.search_backspace(),
                    _ => {}
                }
            }
        }
        if last_tick.elapsed() >= tick_rate {
            app.tick();
            last_tick = std::time::Instant::now();
        }
        while let Ok(msg) = rx.try_recv() {
            app.handle_msg(msg).await;
        }
    }
    Ok(())
}

fn setup_terminal() -> Result<Terminal<CrosstermBackend<Stdout>>> {
    enable_raw_mode()?;
    let mut out = stdout();
    execute!(out, EnterAlternateScreen, EnableMouseCapture)?;
    let backend = CrosstermBackend::new(out);
    Terminal::new(backend).context("init terminal")
}

fn restore_terminal(terminal: &mut Terminal<CrosstermBackend<Stdout>>) -> Result<()> {
    disable_raw_mode()?;
    execute!(
        terminal.backend_mut(),
        LeaveAlternateScreen,
        DisableMouseCapture
    )?;
    terminal.show_cursor().ok();
    Ok(())
}

fn read_user_id() -> Result<i64> {
    if let Ok(s) = std::env::var("DZR_USER_ID") {
        if let Some(id) = dzr_core::parse_user_id(&s) {
            return Ok(id);
        }
    }
    let cfg = config_path()?;
    if let Ok(s) = std::fs::read_to_string(&cfg) {
        if let Some(id) = dzr_core::parse_user_id(s.trim()) {
            return Ok(id);
        }
    }
    eprintln!("Enter Deezer user ID or profile URL:");
    let mut buf = String::new();
    std::io::stdin().read_line(&mut buf).context("stdin")?;
    let id = dzr_core::parse_user_id(buf.trim())
        .ok_or_else(|| anyhow::anyhow!("could not parse user id"))?;
    if let Some(parent) = cfg.parent() {
        std::fs::create_dir_all(parent).ok();
    }
    std::fs::write(&cfg, id.to_string()).ok();
    Ok(id)
}

fn config_path() -> Result<std::path::PathBuf> {
    let dirs = directories::ProjectDirs::from("com", "klear", "dzr")
        .context("ProjectDirs")?;
    Ok(dirs.config_dir().join("user_id"))
}
