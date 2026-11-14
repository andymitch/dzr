package deezer

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	appID   = "447462"
	apiBase = "https://api.deezer.com/"
	connect = "https://connect.deezer.com/2.0/smartlogin"
)

var ErrUnauthorized = errors.New("deezer session expired")

type Session struct {
	AccessToken string `json:"accessToken"`
	UserID      int64  `json:"userId"`
}

func sessionPath() (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "dzr", "session.json"), nil
}

func LoadSession() (*Session, error) {
	p, err := sessionPath()
	if err != nil {
		return nil, err
	}
	b, err := os.ReadFile(p)
	if err != nil {
		return nil, err
	}
	var s Session
	if err := json.Unmarshal(b, &s); err != nil || s.AccessToken == "" {
		return nil, errors.New("invalid session file")
	}
	return &s, nil
}

func (s *Session) Save() error {
	p, err := sessionPath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		return err
	}
	b, _ := json.Marshal(s)
	return os.WriteFile(p, b, 0o600)
}

func ClearSession() {
	if p, err := sessionPath(); err == nil {
		os.Remove(p)
	}
}

type LoginCode struct {
	Code string
	URL  string
}

func StartLogin(ctx context.Context) (*LoginCode, error) {
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, connect+"?app_id="+appID, nil)
	var out struct {
		Data struct {
			SmartLoginCode string `json:"smartLoginCode"`
			URL            string `json:"url"`
		} `json:"data"`
	}
	if err := doJSON(req, &out); err != nil {
		return nil, err
	}
	return &LoginCode{Code: out.Data.SmartLoginCode, URL: out.Data.URL}, nil
}

func PollLogin(ctx context.Context, code string) (*Session, error) {
	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()
	for {
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, connect+"/"+code+"?app_id="+appID, nil)
		var out struct {
			Data struct {
				AccessToken *string `json:"accessToken"`
				UserID      *int64  `json:"userId"`
			} `json:"data"`
		}
		if err := doJSON(req, &out); err == nil && out.Data.AccessToken != nil && out.Data.UserID != nil {
			s := &Session{AccessToken: *out.Data.AccessToken, UserID: *out.Data.UserID}
			return s, s.Save()
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-tick.C:
		}
	}
}

type Kind string

const (
	KindTrack    Kind = "track"
	KindAlbum    Kind = "album"
	KindPlaylist Kind = "playlist"
	KindArtist   Kind = "artist"
	KindFlow     Kind = "flow"
	KindMix      Kind = "mix"
	KindHeader   Kind = "header"
)

type Item struct {
	Kind     Kind
	ID       string
	Title    string
	Subtitle string
	Duration time.Duration
	ArtistID int64
	AlbumID  int64
	Readable bool
}

type resource struct {
	Type       string          `json:"type"`
	ID         string          `json:"id"`
	Attributes json.RawMessage `json:"attributes"`
	Included   []resource      `json:"included"`
}

type attributes struct {
	Title       string `json:"title"`
	Name        string `json:"name"`
	Description string `json:"description"`
	ArtistName  string `json:"artistName"`
	ArtistID    int64  `json:"artistId"`
	AlbumName   string `json:"albumName"`
	AlbumID     int64  `json:"albumId"`
	Duration    int    `json:"duration"`
	NbTracks    int    `json:"nb_tracks"`
	NbFans      int    `json:"nbFans"`
	ReleaseDate string `json:"release_date"`
	Readable    *bool  `json:"readable"`
	Artist      struct {
		Name string `json:"name"`
	} `json:"artist"`
	Creator struct {
		Name string `json:"name"`
	} `json:"creator"`
}

func (r resource) item() Item {
	var a attributes
	json.Unmarshal(r.Attributes, &a)
	it := Item{ID: r.ID, Readable: a.Readable == nil || *a.Readable}
	switch strings.TrimSuffix(r.Type, "s") {
	case "track":
		it.Kind, it.Title = KindTrack, a.Title
		it.Subtitle = a.ArtistName + " · " + a.AlbumName
		it.Duration = time.Duration(a.Duration) * time.Second
		it.ArtistID, it.AlbumID = a.ArtistID, a.AlbumID
	case "album":
		it.Kind, it.Title = KindAlbum, a.Name
		it.Subtitle = a.Artist.Name
		if len(a.ReleaseDate) >= 4 {
			it.Subtitle += " · " + a.ReleaseDate[:4]
		}
	case "playlist":
		it.Kind, it.Title = KindPlaylist, a.Name
		it.Subtitle = fmt.Sprintf("%s · %d tracks", a.Creator.Name, a.NbTracks)
	case "artist":
		it.Kind, it.Title = KindArtist, a.Name
		it.Subtitle = fmt.Sprintf("%d fans", a.NbFans)
	case "multiflow":
		it.Kind, it.Title, it.Subtitle = KindFlow, a.Title, "Flow"
	default:
		it.Kind, it.Title = Kind(r.Type), a.Title+a.Name
	}
	return it
}

func items(rs []resource) []Item {
	out := make([]Item, 0, len(rs))
	for _, r := range rs {
		out = append(out, r.item())
	}
	return out
}

type Client struct {
	s *Session
}

func NewClient(s *Session) *Client { return &Client{s: s} }

func (c *Client) Use(s *Session) { c.s = s }

func (c *Client) LikedIDs(ctx context.Context) (map[string]bool, error) {
	r, err := c.one(ctx, c.user("loved-tracks"))
	ids := map[string]bool{}
	for _, t := range r.Included {
		ids[t.ID] = true
	}
	return ids, err
}

func doJSON(req *http.Request, out any) error {
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode == http.StatusUnauthorized {
		return ErrUnauthorized
	}
	if res.StatusCode >= 300 {
		return fmt.Errorf("deezer: %s %s", req.URL.Path, res.Status)
	}
	if out == nil {
		return nil
	}
	return json.NewDecoder(res.Body).Decode(out)
}

func (c *Client) call(ctx context.Context, method, path string, out any) error {
	u := path
	if !strings.HasPrefix(u, "https://") {
		u = apiBase + path
	}
	req, err := http.NewRequestWithContext(ctx, method, u, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.s.AccessToken)
	return doJSON(req, out)
}

func (c *Client) one(ctx context.Context, path string) (resource, error) {
	var out struct {
		Data resource `json:"data"`
	}
	err := c.call(ctx, http.MethodGet, path, &out)
	return out.Data, err
}

func (c *Client) many(ctx context.Context, path string) ([]resource, error) {
	var out struct {
		Data []resource `json:"data"`
	}
	err := c.call(ctx, http.MethodGet, path, &out)
	return out.Data, err
}

func (c *Client) user(path string) string {
	return "platform/gcast/user/" + strconv.FormatInt(c.s.UserID, 10) + "/" + path
}

type Section struct {
	Title string
	Items []Item
}

func (c *Client) Home(ctx context.Context) ([]Section, error) {
	flows, err := c.one(ctx, "platform/generic/user/"+strconv.FormatInt(c.s.UserID, 10)+"/multiflow")
	if err != nil {
		return nil, err
	}
	mixes := []Item{{Kind: KindMix, ID: "discovery", Title: "Discovery", Subtitle: "Mix"}}
	for i := 0; i < 3; i++ {
		mixes = append(mixes, Item{Kind: KindMix, ID: "inspired-by-" + strconv.Itoa(i), Title: fmt.Sprintf("Inspired by #%d", i+1), Subtitle: "Mix"})
	}
	recs, err := c.one(ctx, c.user("recommended-playlists"))
	return []Section{
		{"Flow", items(flows.Included)},
		{"Mixes", mixes},
		{"Recommended playlists", items(recs.Included)},
	}, err
}

func (c *Client) Library(ctx context.Context) ([]Section, error) {
	pl, err := c.one(ctx, c.user("playlists"))
	if err != nil {
		return nil, err
	}
	albums, err := c.one(ctx, c.user("loved-albums?start=0&limit=500"))
	if err != nil {
		return nil, err
	}
	artists, err := c.one(ctx, c.user("loved-artists"))
	return []Section{
		{"Playlists", items(pl.Included)},
		{"Albums", items(albums.Included)},
		{"Artists", items(artists.Included)},
	}, err
}

func (c *Client) Search(ctx context.Context, q string) ([]Section, error) {
	q = url.QueryEscape(q)
	tracks, err := c.many(ctx, "platform/generic/search/track?q="+q)
	if err != nil {
		return nil, err
	}
	artists, _ := c.many(ctx, "platform/generic/search/artist?q="+q)
	albums, _ := c.one(ctx, "platform/generic/search/album?q="+q)
	playlists, _ := c.many(ctx, "platform/generic/search/playlist?q="+q)
	return []Section{
		{"Tracks", items(tracks)},
		{"Artists", items(artists)},
		{"Albums", items(albums.Included)},
		{"Playlists", items(playlists)},
	}, nil
}

func (c *Client) Open(ctx context.Context, it Item) ([]Section, error) {
	switch it.Kind {
	case KindPlaylist:
		r, err := c.one(ctx, "platform/generic/playlist/"+it.ID)
		return []Section{{it.Title, items(r.Included)}}, err
	case KindAlbum:
		r, err := c.one(ctx, "platform/generic/album/"+it.ID)
		return []Section{{it.Title, items(r.Included)}}, err
	case KindFlow:
		r, err := c.one(ctx, "platform/generic/user/"+strconv.FormatInt(c.s.UserID, 10)+"/multiflow/"+it.ID)
		return []Section{{it.Title, items(r.Included)}}, err
	case KindMix:
		r, err := c.one(ctx, "platform/generic/smarttracklist/"+it.ID)
		return []Section{{it.Title, items(r.Included)}}, err
	case KindArtist:
		top, err := c.one(ctx, "platform/generic/artist/"+it.ID+"/top-tracks")
		if err != nil {
			return nil, err
		}
		albums, _ := c.one(ctx, "platform/generic/artist/"+it.ID+"/albums")
		similar, _ := c.one(ctx, "platform/generic/artist/"+it.ID+"/similar-artists")
		sim := items(similar.Included)
		if len(sim) > 20 {
			sim = sim[:20]
		}
		return []Section{
			{"Top tracks", items(top.Included)},
			{"Albums", items(albums.Included)},
			{"Similar artists", sim},
		}, nil
	}
	return nil, fmt.Errorf("can't open %s", it.Kind)
}

type Quality string

const (
	QualityFlac Quality = "flac"
	Quality320  Quality = "320"
	Quality128  Quality = "128"
)

func (c *Client) StreamURL(ctx context.Context, trackID string, q Quality) (string, Quality, error) {
	var out struct {
		Data struct {
			Attributes struct {
				URL     string `json:"url"`
				URL320  string `json:"url_320"`
				URLFlac string `json:"url_flac"`
			} `json:"attributes"`
		} `json:"data"`
	}
	if err := c.call(ctx, http.MethodGet, "platform/gcast/track/"+trackID+"/streamUrls", &out); err != nil {
		return "", "", err
	}
	a := out.Data.Attributes
	order := []struct {
		q Quality
		u string
	}{{QualityFlac, a.URLFlac}, {Quality320, a.URL320}, {Quality128, a.URL}}
	for i, o := range order {
		if o.q == q {
			order = order[i:]
			break
		}
	}
	for _, o := range order {
		if o.u != "" {
			return o.u, o.q, nil
		}
	}
	return "", "", errors.New("track not available to stream")
}

func (c *Client) SetLiked(ctx context.Context, trackID string, liked bool) error {
	m := http.MethodPost
	if !liked {
		m = http.MethodDelete
	}
	return c.call(ctx, m, "platform/generic/track/"+trackID+"/like", nil)
}
