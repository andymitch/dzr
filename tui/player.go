package main

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/andymitch/dzr/tui/internal/deezer"
	"github.com/gopxl/beep/v2"
	"github.com/gopxl/beep/v2/flac"
	"github.com/gopxl/beep/v2/mp3"
	"github.com/gopxl/beep/v2/speaker"
)

const outRate = beep.SampleRate(48000)

type loaded struct {
	track   deezer.Item
	quality deezer.Quality
	data    []byte
	flac    bool
}

type Player struct {
	client  *deezer.Client
	quality deezer.Quality
	onEnd   func()

	mu       sync.Mutex
	stream   beep.StreamSeekCloser
	format   beep.Format
	ctrl     *beep.Ctrl
	current  *loaded
	cache    map[string]*loaded
	inflight map[string]chan struct{}
}

func NewPlayer(c *deezer.Client, q deezer.Quality, onEnd func()) (*Player, error) {
	if err := speaker.Init(outRate, outRate.N(100*time.Millisecond)); err != nil {
		return nil, err
	}
	return &Player{client: c, quality: q, onEnd: onEnd, cache: map[string]*loaded{}, inflight: map[string]chan struct{}{}}, nil
}

func (p *Player) fetch(ctx context.Context, t deezer.Item) (*loaded, error) {
	p.mu.Lock()
	if l, ok := p.cache[t.ID]; ok {
		p.mu.Unlock()
		return l, nil
	}
	if ch, ok := p.inflight[t.ID]; ok {
		p.mu.Unlock()
		<-ch
		return p.fetch(ctx, t)
	}
	ch := make(chan struct{})
	p.inflight[t.ID] = ch
	p.mu.Unlock()
	defer func() {
		p.mu.Lock()
		delete(p.inflight, t.ID)
		p.mu.Unlock()
		close(ch)
	}()

	u, q, err := p.client.StreamURL(ctx, t.ID, p.quality)
	if err != nil {
		return nil, err
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("stream: %s", res.Status)
	}
	data, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, err
	}
	l := &loaded{track: t, quality: q, data: data, flac: strings.Contains(res.Header.Get("Content-Type"), "flac")}
	p.mu.Lock()
	if len(p.cache) > 4 {
		clear(p.cache)
	}
	p.cache[t.ID] = l
	p.mu.Unlock()
	return l, nil
}

func (p *Player) Prefetch(t deezer.Item) {
	go p.fetch(context.Background(), t)
}

func (p *Player) Play(ctx context.Context, t deezer.Item) (deezer.Quality, error) {
	l, err := p.fetch(ctx, t)
	if err != nil {
		return "", err
	}
	var s beep.StreamSeekCloser
	var f beep.Format
	if l.flac {
		s, f, err = flac.Decode(bytes.NewReader(l.data))
	} else {
		s, f, err = mp3.Decode(io.NopCloser(bytes.NewReader(l.data)))
	}
	if err != nil {
		return "", err
	}
	var src beep.Streamer = s
	if f.SampleRate != outRate {
		src = beep.Resample(4, f.SampleRate, outRate, s)
	}
	ctrl := &beep.Ctrl{Streamer: beep.Seq(src, beep.Callback(p.onEnd))}

	speaker.Clear()
	p.mu.Lock()
	if p.stream != nil {
		p.stream.Close()
	}
	p.stream, p.format, p.ctrl, p.current = s, f, ctrl, l
	p.mu.Unlock()
	speaker.Play(ctrl)
	return l.quality, nil
}

func (p *Player) TogglePause() {
	speaker.Lock()
	if p.ctrl != nil {
		p.ctrl.Paused = !p.ctrl.Paused
	}
	speaker.Unlock()
}

func (p *Player) Paused() bool {
	speaker.Lock()
	defer speaker.Unlock()
	return p.ctrl == nil || p.ctrl.Paused
}

func (p *Player) Position() (time.Duration, time.Duration) {
	speaker.Lock()
	defer speaker.Unlock()
	if p.stream == nil {
		return 0, 0
	}
	sr := p.format.SampleRate
	return sr.D(p.stream.Position()), sr.D(p.stream.Len())
}

func (p *Player) Seek(d time.Duration) {
	speaker.Lock()
	defer speaker.Unlock()
	if p.stream == nil {
		return
	}
	pos := p.stream.Position() + p.format.SampleRate.N(d)
	pos = max(0, min(pos, p.stream.Len()-1))
	p.stream.Seek(pos)
}
