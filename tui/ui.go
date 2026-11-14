package main

import (
	"context"
	"errors"
	"fmt"
	"math/rand/v2"
	"strings"
	"time"

	"charm.land/bubbles/v2/textinput"
	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"
	"github.com/andymitch/dzr/tui/internal/deezer"
	"github.com/charmbracelet/x/ansi"
)

var (
	accent  = lipgloss.Color("#A238FF")
	dim     = lipgloss.NewStyle().Foreground(lipgloss.Color("#6B6578"))
	bold    = lipgloss.NewStyle().Bold(true)
	tabOn   = lipgloss.NewStyle().Bold(true).Foreground(lipgloss.Color("#FFFFFF")).Background(accent).Padding(0, 1)
	tabOff  = lipgloss.NewStyle().Foreground(lipgloss.Color("#B9B3C7")).Padding(0, 1)
	header  = lipgloss.NewStyle().Bold(true).Foreground(accent)
	sel     = lipgloss.NewStyle().Foreground(lipgloss.Color("#FFFFFF")).Background(lipgloss.Color("#3A2560"))
	errText = lipgloss.NewStyle().Foreground(lipgloss.Color("#FF5F87"))
	heart   = lipgloss.NewStyle().Foreground(lipgloss.Color("#FF6FB5")).Render("♥")
)

type tab int

const (
	tabHome tab = iota
	tabLibrary
	tabSearch
)

var tabNames = []string{"Home", "Library", "Search"}

type row struct {
	header string
	item   deezer.Item
}

type page struct {
	title   string
	rows    []row
	cursor  int
	offset  int
	loading bool
	err     error
}

func (p *page) set(secs []deezer.Section) {
	p.rows = nil
	for _, s := range secs {
		if len(s.Items) == 0 {
			continue
		}
		if len(secs) > 1 {
			p.rows = append(p.rows, row{header: s.Title})
		}
		for _, it := range s.Items {
			p.rows = append(p.rows, row{item: it})
		}
	}
	p.cursor = 0
	p.move(0)
}

func (p *page) move(d int) {
	if len(p.rows) == 0 {
		return
	}
	step := 1
	if d < 0 {
		step = -1
	}
	c := max(0, min(len(p.rows)-1, p.cursor+d))
	for c >= 0 && c < len(p.rows) && p.rows[c].header != "" {
		c += step
	}
	if c < 0 || c >= len(p.rows) {
		return
	}
	p.cursor = c
}

func (p *page) tracks() ([]deezer.Item, int) {
	var ts []deezer.Item
	at := 0
	for i, r := range p.rows {
		if r.header == "" && r.item.Kind == deezer.KindTrack && r.item.Readable {
			if i == p.cursor {
				at = len(ts)
			}
			ts = append(ts, r.item)
		}
	}
	return ts, at
}

type repeatMode int

const (
	repeatOff repeatMode = iota
	repeatAll
	repeatOne
)

type model struct {
	client *deezer.Client
	player *Player

	width, height int
	tab           tab
	stacks        [3][]*page
	search        textinput.Model
	typing        bool

	queue   []deezer.Item
	orig    []deezer.Item
	qi      int
	shuffle bool
	repeat  repeatMode
	quality deezer.Quality
	liked   map[string]bool
	status  string

	login     *deezer.LoginCode
	loginErr  error
	cancelPol context.CancelFunc
}

type (
	loadedMsg struct {
		p    *page
		secs []deezer.Section
		err  error
	}
	playedMsg struct {
		q   deezer.Quality
		err error
		id  string
	}
	endedMsg    struct{}
	tickMsg     struct{}
	likedMsg    map[string]bool
	loginMsg    *deezer.LoginCode
	loggedInMsg *deezer.Session
	loginErrMsg struct{ err error }
	statusMsg   string
)

func newModel(c *deezer.Client, p *Player, loggedIn bool) *model {
	ti := textinput.New()
	ti.Placeholder = "Search tracks, artists, albums, playlists"
	ti.Prompt = "/ "
	m := &model{client: c, player: p, search: ti, liked: map[string]bool{}}
	for t := range m.stacks {
		m.stacks[t] = []*page{{title: tabNames[t]}}
	}
	if !loggedIn {
		m.login = &deezer.LoginCode{}
	}
	return m
}

func (m *model) Init() tea.Cmd {
	if m.login != nil {
		return m.startLogin()
	}
	return tea.Batch(m.loadRoots(), tick())
}

func tick() tea.Cmd {
	return tea.Tick(500*time.Millisecond, func(time.Time) tea.Msg { return tickMsg{} })
}

func (m *model) startLogin() tea.Cmd {
	return func() tea.Msg {
		code, err := deezer.StartLogin(context.Background())
		if err != nil {
			return loginErrMsg{err}
		}
		return loginMsg(code)
	}
}

func (m *model) loadRoots() tea.Cmd {
	home, lib := m.stacks[tabHome][0], m.stacks[tabLibrary][0]
	return tea.Batch(
		m.load(home, func(ctx context.Context) ([]deezer.Section, error) { return m.client.Home(ctx) }),
		m.load(lib, func(ctx context.Context) ([]deezer.Section, error) { return m.client.Library(ctx) }),
		func() tea.Msg {
			ids, _ := m.client.LikedIDs(context.Background())
			return likedMsg(ids)
		},
	)
}

func (m *model) load(p *page, f func(context.Context) ([]deezer.Section, error)) tea.Cmd {
	p.loading, p.err = true, nil
	return func() tea.Msg {
		secs, err := f(context.Background())
		return loadedMsg{p, secs, err}
	}
}

func (m *model) page() *page {
	s := m.stacks[m.tab]
	return s[len(s)-1]
}

func (m *model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width, m.height = msg.Width, msg.Height
		m.search.SetWidth(msg.Width - 4)
	case loginMsg:
		m.login, m.loginErr = msg, nil
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
		m.cancelPol = cancel
		return m, func() tea.Msg {
			s, err := deezer.PollLogin(ctx, msg.Code)
			if err != nil {
				return loginErrMsg{err}
			}
			return loggedInMsg(s)
		}
	case loginErrMsg:
		m.loginErr = msg.err
	case loggedInMsg:
		m.client.Use(msg)
		m.login = nil
		return m, tea.Batch(m.loadRoots(), tick())
	case loadedMsg:
		msg.p.loading, msg.p.err = false, msg.err
		if errors.Is(msg.err, deezer.ErrUnauthorized) {
			return m, m.relogin()
		}
		msg.p.set(msg.secs)
	case likedMsg:
		m.liked = msg
	case playedMsg:
		if errors.Is(msg.err, deezer.ErrUnauthorized) {
			return m, m.relogin()
		}
		if m.qi < len(m.queue) && m.queue[m.qi].ID == msg.id {
			m.quality, m.status = msg.q, ""
			if msg.err != nil {
				m.status = "can't play: " + msg.err.Error()
			}
			if n, ok := m.peekNext(); ok {
				m.player.Prefetch(n)
			}
		}
	case endedMsg:
		return m, m.advance(1, true)
	case statusMsg:
		m.status = string(msg)
	case tickMsg:
		return m, tick()
	case tea.KeyPressMsg:
		return m.key(msg)
	}
	return m, nil
}

func (m *model) relogin() tea.Cmd {
	deezer.ClearSession()
	m.login = &deezer.LoginCode{}
	return m.startLogin()
}

func (m *model) key(k tea.KeyPressMsg) (tea.Model, tea.Cmd) {
	s := k.String()
	if s == "ctrl+c" {
		return m, tea.Quit
	}
	if m.login != nil {
		if s == "q" || s == "esc" {
			return m, tea.Quit
		}
		if s == "r" && m.loginErr != nil {
			return m, m.startLogin()
		}
		return m, nil
	}
	if m.typing {
		switch s {
		case "esc":
			m.typing = false
			m.search.Blur()
			return m, nil
		case "enter":
			m.typing = false
			m.search.Blur()
			q := strings.TrimSpace(m.search.Value())
			if q == "" {
				return m, nil
			}
			root := &page{title: "Search: " + q}
			m.stacks[tabSearch] = []*page{root}
			return m, m.load(root, func(ctx context.Context) ([]deezer.Section, error) { return m.client.Search(ctx, q) })
		}
		var cmd tea.Cmd
		m.search, cmd = m.search.Update(k)
		return m, cmd
	}

	p := m.page()
	switch s {
	case "q":
		return m, tea.Quit
	case "1", "2", "3":
		m.tab = tab(s[0] - '1')
	case "tab":
		m.tab = (m.tab + 1) % 3
	case "/":
		m.tab, m.typing = tabSearch, true
		return m, m.search.Focus()
	case "up", "k":
		p.move(-1)
	case "down", "j":
		p.move(1)
	case "pgup":
		p.move(-m.listHeight())
	case "pgdown":
		p.move(m.listHeight())
	case "home", "g":
		p.cursor = 0
		p.move(0)
	case "end", "G":
		p.cursor = len(p.rows) - 1
		p.move(0)
	case "esc", "backspace", "h":
		if st := m.stacks[m.tab]; len(st) > 1 {
			m.stacks[m.tab] = st[:len(st)-1]
		}
	case "enter", "l", "right":
		return m, m.open()
	case "space":
		m.player.TogglePause()
	case "n":
		return m, m.advance(1, false)
	case "p":
		if pos, _ := m.player.Position(); pos > 3*time.Second {
			m.player.Seek(-pos)
			return m, nil
		}
		return m, m.advance(-1, false)
	case ".", "shift+right":
		m.player.Seek(10 * time.Second)
	case ",", "shift+left":
		m.player.Seek(-10 * time.Second)
	case "s":
		m.toggleShuffle()
	case "r":
		m.repeat = (m.repeat + 1) % 3
	case "f":
		return m, m.toggleLike()
	case "a":
		if p.cursor < len(p.rows) {
			if it := p.rows[p.cursor].item; it.Kind == deezer.KindTrack && it.Readable {
				m.queue = append(m.queue, it)
				m.orig = append(m.orig, it)
				m.status = "queued " + it.Title
			}
		}
	}
	return m, nil
}

func (m *model) open() tea.Cmd {
	p := m.page()
	if p.cursor >= len(p.rows) || p.rows[p.cursor].header != "" {
		return nil
	}
	it := p.rows[p.cursor].item
	if it.Kind == deezer.KindTrack {
		ts, at := p.tracks()
		if !it.Readable || len(ts) == 0 {
			m.status = "not available to stream"
			return nil
		}
		m.queue, m.orig, m.qi = ts, append([]deezer.Item(nil), ts...), at
		if m.shuffle {
			m.shuffleRest()
		}
		return m.playCurrent()
	}
	np := &page{title: it.Title}
	m.stacks[m.tab] = append(m.stacks[m.tab], np)
	return m.load(np, func(ctx context.Context) ([]deezer.Section, error) { return m.client.Open(ctx, it) })
}

func (m *model) playCurrent() tea.Cmd {
	if m.qi < 0 || m.qi >= len(m.queue) {
		return nil
	}
	t := m.queue[m.qi]
	m.status = "loading " + t.Title + "…"
	return func() tea.Msg {
		q, err := m.player.Play(context.Background(), t)
		return playedMsg{q, err, t.ID}
	}
}

func (m *model) peekNext() (deezer.Item, bool) {
	if m.repeat == repeatOne {
		return deezer.Item{}, false
	}
	n := m.qi + 1
	if n >= len(m.queue) {
		if m.repeat != repeatAll || len(m.queue) == 0 {
			return deezer.Item{}, false
		}
		n = 0
	}
	return m.queue[n], true
}

func (m *model) advance(d int, auto bool) tea.Cmd {
	if len(m.queue) == 0 {
		return nil
	}
	if auto && m.repeat == repeatOne {
		return m.playCurrent()
	}
	n := m.qi + d
	if n >= len(m.queue) || n < 0 {
		if m.repeat != repeatAll {
			if auto {
				m.status = "end of queue"
			}
			return nil
		}
		n = (n + len(m.queue)) % len(m.queue)
	}
	m.qi = n
	return m.playCurrent()
}

func (m *model) shuffleRest() {
	rest := m.queue[m.qi+1:]
	rand.Shuffle(len(rest), func(i, j int) { rest[i], rest[j] = rest[j], rest[i] })
}

func (m *model) toggleShuffle() {
	m.shuffle = !m.shuffle
	if len(m.queue) == 0 {
		return
	}
	if m.shuffle {
		m.queue = append([]deezer.Item(nil), m.queue...)
		m.shuffleRest()
		return
	}
	cur := m.queue[m.qi]
	m.queue = append([]deezer.Item(nil), m.orig...)
	for i, t := range m.queue {
		if t.ID == cur.ID {
			m.qi = i
			break
		}
	}
}

func (m *model) toggleLike() tea.Cmd {
	if m.qi >= len(m.queue) {
		return nil
	}
	t := m.queue[m.qi]
	like := !m.liked[t.ID]
	m.liked[t.ID] = like
	return func() tea.Msg {
		if err := m.client.SetLiked(context.Background(), t.ID, like); err != nil {
			return statusMsg("couldn't update like: " + err.Error())
		}
		if like {
			return statusMsg("added to Liked tracks")
		}
		return statusMsg("removed from Liked tracks")
	}
}

func (m *model) listHeight() int { return max(1, m.height-7) }

func (m *model) View() tea.View {
	v := tea.NewView(m.render())
	v.AltScreen = true
	return v
}

func (m *model) render() string {
	if m.width == 0 {
		return ""
	}
	if m.login != nil {
		return m.renderLogin()
	}
	var b strings.Builder
	var tabs []string
	for i, n := range tabNames {
		st := tabOff
		if tab(i) == m.tab {
			st = tabOn
		}
		tabs = append(tabs, st.Render(fmt.Sprintf("%d %s", i+1, n)))
	}
	b.WriteString(lipgloss.NewStyle().Foreground(accent).Bold(true).Render(" dzr ") + " " + strings.Join(tabs, " ") + "\n")

	p := m.page()
	var crumbs []string
	for _, pg := range m.stacks[m.tab] {
		crumbs = append(crumbs, pg.title)
	}
	b.WriteString(dim.Render(ansi.Truncate(" "+strings.Join(crumbs, " › "), m.width, "…")) + "\n")

	h := m.listHeight()
	if m.tab == tabSearch {
		b.WriteString(" " + m.search.View() + "\n")
		h--
	}
	b.WriteString(m.renderList(p, h))
	b.WriteString(m.renderNowPlaying())
	return b.String()
}

func (m *model) renderList(p *page, h int) string {
	var lines []string
	switch {
	case p.loading:
		lines = []string{dim.Render("  loading…")}
	case p.err != nil:
		lines = []string{errText.Render("  " + p.err.Error())}
	case len(p.rows) == 0 && m.tab == tabSearch && len(m.stacks[tabSearch]) == 1 && p.title == "Search":
		lines = []string{dim.Render("  press / to search")}
	case len(p.rows) == 0:
		lines = []string{dim.Render("  nothing here")}
	default:
		if p.cursor < p.offset {
			p.offset = p.cursor
		}
		if p.cursor >= p.offset+h {
			p.offset = p.cursor - h + 1
		}
		if p.offset > 0 && p.rows[p.offset-1].header != "" && p.cursor-p.offset+1 < h {
			p.offset--
		}
		for i := p.offset; i < min(len(p.rows), p.offset+h); i++ {
			lines = append(lines, m.renderRow(p.rows[i], i == p.cursor))
		}
	}
	for len(lines) < h {
		lines = append(lines, "")
	}
	return strings.Join(lines, "\n") + "\n"
}

func (m *model) renderRow(r row, selected bool) string {
	if r.header != "" {
		return header.Render(" " + r.header)
	}
	it := r.item
	icon := map[deezer.Kind]string{deezer.KindTrack: "♪", deezer.KindAlbum: "◉", deezer.KindPlaylist: "≡", deezer.KindArtist: "☺", deezer.KindFlow: "∿", deezer.KindMix: "✦"}[it.Kind]
	playing := m.qi < len(m.queue) && m.queue[m.qi].ID == it.ID && it.Kind == deezer.KindTrack
	if playing {
		icon = "▶"
	}
	right := ""
	if it.Kind == deezer.KindTrack {
		right = fmtDur(it.Duration)
		if m.liked[it.ID] {
			right = "♥ " + right
		}
	}
	w := m.width - 2
	titleW := max(10, w*5/10)
	title := ansi.Truncate(it.Title, titleW, "…")
	subW := max(0, w-4-titleW-lipgloss.Width(right)-2)
	sub := ansi.Truncate(it.Subtitle, subW, "…")
	line := fmt.Sprintf(" %s %-*s  %-*s %s", icon, titleW, title, subW, sub, right)
	switch {
	case selected:
		return sel.Render(ansi.Truncate(line, m.width, ""))
	case it.Kind == deezer.KindTrack && !it.Readable:
		return dim.Render(line)
	case playing:
		return lipgloss.NewStyle().Foreground(accent).Render(line)
	}
	return " " + icon + " " + fmt.Sprintf("%-*s", titleW, title) + "  " + dim.Render(fmt.Sprintf("%-*s", subW, sub)) + " " + right
}

func (m *model) renderNowPlaying() string {
	line := strings.Repeat("─", m.width)
	if m.qi >= len(m.queue) || m.quality == "" {
		return dim.Render(line) + "\n " + dim.Render("nothing playing") + "\n" + m.help()
	}
	t := m.queue[m.qi]
	pos, total := m.player.Position()
	state := "▶"
	if m.player.Paused() {
		state = "⏸"
	}
	like := ""
	if m.liked[t.ID] {
		like = " " + heart
	}
	flags := []string{}
	if m.shuffle {
		flags = append(flags, "shuffle")
	}
	if m.repeat == repeatAll {
		flags = append(flags, "repeat")
	} else if m.repeat == repeatOne {
		flags = append(flags, "repeat one")
	}
	if m.quality != "" {
		flags = append(flags, string(m.quality))
	}
	info := fmt.Sprintf(" %s %s%s  %s", state, bold.Render(t.Title), like, dim.Render(t.Subtitle))
	meta := dim.Render(fmt.Sprintf("%d/%d  %s ", m.qi+1, len(m.queue), strings.Join(flags, " · ")))
	info = ansi.Truncate(info, max(0, m.width-lipgloss.Width(meta)), "…")
	info += strings.Repeat(" ", max(0, m.width-lipgloss.Width(info)-lipgloss.Width(meta))) + meta

	times := fmt.Sprintf(" %s / %s ", fmtDur(pos), fmtDur(total))
	barW := max(0, m.width-lipgloss.Width(times)-2)
	filled := 0
	if total > 0 {
		filled = int(float64(barW) * float64(pos) / float64(total))
	}
	bar := " " + lipgloss.NewStyle().Foreground(accent).Render(strings.Repeat("━", filled)) + dim.Render(strings.Repeat("─", barW-filled)) + times
	status := ""
	if m.status != "" {
		status = "  " + m.status
	}
	return dim.Render(line) + "\n" + info + "\n" + bar + "\n" + m.help() + dim.Render(status)
}

func (m *model) help() string {
	return dim.Render(ansi.Truncate(" enter open/play · esc back · space pause · n/p next/prev · ,/. seek · s shuffle · r repeat · f like · a queue · / search · q quit", m.width, "…"))
}

func (m *model) renderLogin() string {
	var b strings.Builder
	b.WriteString("\n " + header.Render("dzr") + bold.Render("  Sign in to Deezer") + "\n\n")
	switch {
	case m.loginErr != nil:
		b.WriteString(" " + errText.Render(m.loginErr.Error()) + "\n\n " + dim.Render("r retry · q quit"))
	case m.login.Code == "":
		b.WriteString(" " + dim.Render("getting a code…"))
	default:
		link := ansi.SetHyperlink(m.login.URL) + m.login.URL + ansi.ResetHyperlink()
		b.WriteString(" Open " + lipgloss.NewStyle().Foreground(accent).Underline(true).Render(link) + " on any device signed in to Deezer,\n")
		b.WriteString(" or go to dzr.fm/tv and enter the code:\n\n")
		b.WriteString("   " + tabOn.Render(strings.ToUpper(m.login.Code)) + "\n\n")
		b.WriteString(" " + dim.Render("waiting for approval… (q to quit)"))
	}
	return b.String()
}

func fmtDur(d time.Duration) string {
	s := int(d.Seconds())
	return fmt.Sprintf("%d:%02d", s/60, s%60)
}
