use crate::app::{App, Pane, ResStatus, SidebarEntry};
use ratatui::layout::{Alignment, Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::symbols::border;
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Clear, List, ListItem, ListState, Paragraph, Wrap};
use ratatui::Frame;
use ratatui_image::StatefulImage;

fn rounded_block() -> Block<'static> {
    Block::default().borders(Borders::ALL).border_set(border::ROUNDED)
}

// taller for bigger art; meta sits at top, leaves room
const NOW_PLAYING_HEIGHT: u16 = 7;

pub fn draw(f: &mut Frame, app: &mut App) {
    let area = f.area();
    let vertical = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Min(8),
            Constraint::Length(NOW_PLAYING_HEIGHT),
            Constraint::Length(1),
        ])
        .split(area);

    let cols = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([Constraint::Length(28), Constraint::Min(40)])
        .split(vertical[0]);

    app.sidebar_rect = cols[0];
    app.tracks_rect = cols[1];
    draw_sidebar(f, app, cols[0]);
    draw_tracks(f, app, cols[1]);
    draw_now(f, app, vertical[1]);
    draw_status(f, app, vertical[2]);

    if app.is_search_active() {
        draw_search_overlay(f, app, area);
    }
    if app.help_open {
        draw_help_overlay(f, area);
    }
}

fn draw_sidebar(f: &mut Frame, app: &mut App, area: Rect) {
    let entries = app.sidebar_entries();
    let items: Vec<ListItem> = entries
        .iter()
        .map(|e| match e {
            SidebarEntry::Section(s) => ListItem::new(Line::from(Span::styled(
                s.clone(),
                Style::default().fg(Color::DarkGray).add_modifier(Modifier::BOLD),
            ))),
            SidebarEntry::Action(_, label) => ListItem::new(Line::from(Span::raw(label.clone()))),
            SidebarEntry::Playlist(_, title, n) => ListItem::new(Line::from(vec![
                Span::raw(title.clone()),
                Span::styled(format!(" · {n}"), Style::default().fg(Color::DarkGray)),
            ])),
        })
        .collect();
    let mut state = ListState::default();
    state.select(Some(app.sidebar_idx));
    *state.offset_mut() = app.sidebar_offset;
    let title_user = app
        .profile
        .as_ref()
        .map(|u| u.name.as_str())
        .unwrap_or("dzr");
    let block = rounded_block().title(Span::styled(
        format!(" {title_user} "),
        sidebar_title_style(app.focus == Pane::Sidebar),
    ));
    let list = List::new(items)
        .block(block)
        .highlight_style(highlight_style(app.focus == Pane::Sidebar))
        .highlight_symbol("▎ ");
    f.render_stateful_widget(list, area, &mut state);
    app.sidebar_offset = state.offset();
}

fn draw_tracks(f: &mut Frame, app: &mut App, area: Rect) {
    let current_id = app.current_track().map(|t| t.id);
    let items: Vec<ListItem> = app
        .active_tracks
        .iter()
        .map(|t| {
            let status = app
                .resolutions
                .get(&t.id)
                .map(|s| s.0)
                .unwrap_or(ResStatus::Idle);
            let is_current = current_id == Some(t.id);
            let (marker, marker_style) = if is_current {
                let glyph = if app.audio.is_paused() && !app.audio.is_loading() {
                    "⏸"
                } else {
                    "▶"
                };
                (glyph, Style::default().fg(Color::Green).add_modifier(Modifier::BOLD))
            } else {
                match status {
                    ResStatus::Idle => (" ", Style::default()),
                    ResStatus::Resolving => ("…", Style::default().fg(Color::Yellow)),
                    ResStatus::Resolved => (" ", Style::default()),
                    ResStatus::Failed => ("✕", Style::default().fg(Color::Red)),
                }
            };
            let title_style = if status == ResStatus::Failed {
                Style::default().fg(Color::DarkGray)
            } else {
                Style::default()
            };
            ListItem::new(Line::from(vec![
                Span::styled(marker, marker_style),
                Span::raw(" "),
                Span::styled(t.title.clone(), title_style),
                Span::styled(
                    format!(" · {}", t.artist.name),
                    Style::default().fg(Color::DarkGray),
                ),
                Span::styled(
                    format!("  {}", fmt_secs(t.duration)),
                    Style::default().fg(Color::DarkGray),
                ),
            ]))
        })
        .collect();
    let mut state = ListState::default();
    state.select(Some(app.tracks_idx));
    *state.offset_mut() = app.tracks_offset;
    let title = format!(
        " {} · {} {}",
        app.active_title,
        app.active_tracks.len(),
        if app.shuffle { "· shuffle " } else { "" }
    );
    let block = rounded_block()
        .title(Span::styled(title, sidebar_title_style(app.focus == Pane::Tracks)));
    let list = List::new(items)
        .block(block)
        .highlight_style(highlight_style(app.focus == Pane::Tracks))
        .highlight_symbol("▶ ");
    f.render_stateful_widget(list, area, &mut state);
    app.tracks_offset = state.offset();
}

fn draw_now(f: &mut Frame, app: &mut App, area: Rect) {
    let block = rounded_block().title(Span::styled(
        " Now Playing ",
        Style::default().fg(Color::Cyan),
    ));
    let inner = block.inner(area);
    f.render_widget(block, area);

    let padded = Layout::default()
        .direction(Direction::Vertical)
        .horizontal_margin(1)
        .vertical_margin(0)
        .constraints([Constraint::Min(1)])
        .split(inner)[0];

    let cols = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Length(10),
            Constraint::Length(2),
            Constraint::Min(20),
            Constraint::Length(2),
            Constraint::Length(16),
        ])
        .split(padded);

    if let Some(proto) = app.art_protocol.as_mut() {
        let widget = StatefulImage::default();
        f.render_stateful_widget(widget, cols[0], proto);
    } else {
        let placeholder = Paragraph::new("[art]").style(Style::default().fg(Color::DarkGray));
        f.render_widget(placeholder, cols[0]);
    }

    let lines: Vec<Line> = if let Some(t) = app.current_track() {
        let status = app
            .resolutions
            .get(&t.id)
            .map(|s| s.0)
            .unwrap_or(ResStatus::Idle);
        let state_label = match status {
            ResStatus::Resolving => "resolving…",
            ResStatus::Failed => "failed",
            _ if app.audio.is_loading() => "buffering…",
            _ if app.audio.is_playing() => "playing",
            _ if app.audio.is_paused() => "paused",
            _ => "—",
        };
        vec![
            Line::from(Span::styled(
                t.title.clone(),
                Style::default().add_modifier(Modifier::BOLD),
            )),
            Line::from(vec![
                Span::styled(t.artist.name.clone(), Style::default().fg(Color::Gray)),
                Span::styled(
                    format!(" · {}", t.album.title),
                    Style::default().fg(Color::DarkGray),
                ),
            ]),
            Line::from(vec![
                Span::styled(state_label, Style::default().fg(Color::Yellow)),
                Span::raw(format!(
                    "   vol {:>3.0}%   shuffle {}",
                    app.audio.volume() * 100.0,
                    if app.shuffle { "on" } else { "off" }
                )),
            ]),
        ]
    } else {
        vec![Line::from(Span::styled(
            "Press Enter on a track to play.  ? for help.",
            Style::default().fg(Color::DarkGray),
        ))]
    };
    f.render_widget(Paragraph::new(lines).wrap(Wrap { trim: true }), cols[2]);

    draw_viz(f, app, cols[4]);
}

fn draw_viz(f: &mut Frame, app: &mut App, area: Rect) {
    if area.width < 4 || area.height < 2 {
        return;
    }
    let active = app.audio.is_playing();
    let n_bands = app.fft.n_bands();
    let bars: Vec<f32> = if active {
        let samples = app.audio.ring().snapshot();
        app.fft.compute(&samples).to_vec()
    } else {
        vec![0.0; n_bands]
    };

    let h = area.height as usize;
    let half_total = (h as i32) * 2;
    let center_half = half_total / 2;
    let max_dist = center_half.max(1) as f32;
    let bar_w = 1usize;
    let gap = 0usize;
    let min_half = 1i32;

    let mut lines: Vec<Line> = (0..h).map(|_| Line::from("")).collect();
    for b in 0..n_bands {
        let v = bars[b].clamp(0.0, 1.0);
        let half_h = ((v * half_total as f32).round() as i32).max(min_half);
        let top_half = center_half - half_h / 2;
        let bot_half = top_half + half_h;

        for (row_idx, line) in lines.iter_mut().enumerate() {
            let r = row_idx as i32;
            let top_cell = r * 2;
            let bot_cell = r * 2 + 1;
            let top_on = top_cell >= top_half && top_cell < bot_half;
            let bot_on = bot_cell >= top_half && bot_cell < bot_half;
            let ch = match (top_on, bot_on) {
                (true, true) => '█',
                (true, false) => '▀',
                (false, true) => '▄',
                (false, false) => ' ',
            };
            let color = if !active {
                Color::DarkGray
            } else {
                match (top_on, bot_on) {
                    (false, false) => Color::DarkGray,
                    (true, true) => {
                        let td = ((top_cell - center_half).abs() as f32) / max_dist;
                        let bd = ((bot_cell - center_half).abs() as f32) / max_dist;
                        heat(td.max(bd))
                    }
                    (true, false) => heat(((top_cell - center_half).abs() as f32) / max_dist),
                    (false, true) => heat(((bot_cell - center_half).abs() as f32) / max_dist),
                }
            };
            let cell: String = std::iter::repeat(ch).take(bar_w).collect();
            line.spans.push(Span::styled(cell, Style::default().fg(color)));
            if b + 1 < n_bands {
                line.spans.push(Span::raw(" ".repeat(gap)));
            }
        }
    }
    f.render_widget(Paragraph::new(lines), area);
}

fn heat(t: f32) -> Color {
    let t = t.clamp(0.0, 1.0);
    let stops: [(f32, (u8, u8, u8)); 4] = [
        (0.0, (29, 185, 84)),
        (0.45, (220, 200, 40)),
        (0.75, (240, 140, 40)),
        (1.0, (220, 50, 40)),
    ];
    let mut prev = stops[0];
    for &next in &stops[1..] {
        if t <= next.0 {
            let span = (next.0 - prev.0).max(1e-6);
            let f = (t - prev.0) / span;
            return Color::Rgb(
                lerp_u8(prev.1.0, next.1.0, f),
                lerp_u8(prev.1.1, next.1.1, f),
                lerp_u8(prev.1.2, next.1.2, f),
            );
        }
        prev = next;
    }
    let (r, g, b) = stops.last().unwrap().1;
    Color::Rgb(r, g, b)
}

fn lerp_u8(a: u8, b: u8, t: f32) -> u8 {
    let v = a as f32 + (b as f32 - a as f32) * t;
    v.clamp(0.0, 255.0) as u8
}

fn draw_status(f: &mut Frame, app: &App, area: Rect) {
    let text = app
        .status_msg
        .clone()
        .unwrap_or_else(|| "q quit · ? help · / search · Tab switch pane · Space pause · n/p next/prev · s shuffle".into());
    let style = if app.status_msg.is_some() {
        Style::default().fg(Color::Red)
    } else {
        Style::default().fg(Color::DarkGray)
    };
    f.render_widget(
        Paragraph::new(text).style(style).alignment(Alignment::Left),
        area,
    );
}

fn draw_search_overlay(f: &mut Frame, app: &App, area: Rect) {
    let popup = centered_rect(60, 3, area);
    f.render_widget(Clear, popup);
    let s = app.search_buf.clone().unwrap_or_default();
    let block = rounded_block().title(" Search Deezer ");
    let p = Paragraph::new(format!("> {s}_")).block(block);
    f.render_widget(p, popup);
}

fn draw_help_overlay(f: &mut Frame, area: Rect) {
    let popup = centered_rect(60, 18, area);
    f.render_widget(Clear, popup);
    let lines = vec![
        Line::from(Span::styled(
            "Keys",
            Style::default().add_modifier(Modifier::BOLD),
        )),
        Line::from(""),
        Line::from("  j/k or ↑/↓     move"),
        Line::from("  g / G          top / bottom"),
        Line::from("  PgUp / PgDn    page"),
        Line::from("  Tab            switch pane"),
        Line::from("  1 / 2          focus sidebar / tracks"),
        Line::from("  Enter          activate / play"),
        Line::from("  /              search"),
        Line::from("  Esc            close overlay"),
        Line::from("  Space          play / pause"),
        Line::from("  n / p          next / prev"),
        Line::from("  s              toggle shuffle"),
        Line::from("  S              shuffle play active list"),
        Line::from("  + / -          volume"),
        Line::from("  q / Ctrl-C     quit"),
    ];
    let block = rounded_block().title(" Help (?) ");
    f.render_widget(Paragraph::new(lines).block(block), popup);
}

fn centered_rect(width: u16, height: u16, area: Rect) -> Rect {
    let w = width.min(area.width);
    let h = height.min(area.height);
    Rect {
        x: area.x + (area.width - w) / 2,
        y: area.y + (area.height - h) / 2,
        width: w,
        height: h,
    }
}

fn sidebar_title_style(focused: bool) -> Style {
    if focused {
        Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD)
    } else {
        Style::default().fg(Color::DarkGray)
    }
}

fn highlight_style(focused: bool) -> Style {
    if focused {
        Style::default()
            .bg(Color::Rgb(31, 58, 37))
            .add_modifier(Modifier::BOLD)
    } else {
        Style::default().bg(Color::Rgb(30, 30, 30))
    }
}

fn fmt_secs(s: u64) -> String {
    format!("{}:{:02}", s / 60, s % 60)
}
