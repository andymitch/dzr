<script lang="ts">
  import { onMount } from 'svelte';
  import * as api from '$lib/deezer';
  import {
    attachAudio,
    playQueue,
    pause,
    resume,
    next,
    prev,
    seek,
    player,
    prefetch,
    resolutions,
    toggleShuffle,
  } from '$lib/player';
  import {
    shuffleSvg,
    searchSvg,
    heartSvg,
    flowSvg,
    chartsSvg,
    refreshSvg,
    playlistSvg,
    albumSvg,
    artistSvg,
  } from '$lib/icons';

  function viewIcon(v: View): string {
    switch (v) {
      case 'search':
        return searchSvg;
      case 'liked':
        return heartSvg;
      case 'flow':
        return flowSvg;
      case 'charts':
        return chartsSvg;
      case 'playlist':
        return playlistSvg;
      case 'album':
        return albumSvg;
      case 'artist':
        return artistSvg;
    }
  }
  import { loadSettings, setUserId, userId } from '$lib/settings';

  type View = 'search' | 'flow' | 'charts' | 'liked' | 'playlist' | 'album' | 'artist';
  let view = $state<View>('charts');
  let query = $state('');
  let activeTracks = $state<api.Track[]>([]);
  let activeTitle = $state('Charts');
  let searchInput: HTMLInputElement | undefined = $state();
  let lastSearchResults = $state<api.Track[]>([]);
  let lastSearchQuery = $state('');
  let playlists = $state<api.Playlist[]>([]);
  let albums = $state<api.Album[]>([]);
  let artists = $state<api.Artist[]>([]);
  let liked = $state<api.Track[]>([]);
  let libraryTab = $state<'playlists' | 'albums' | 'artists'>('playlists');
  let profile = $state<api.User | null>(null);
  let error = $state('');
  let busy = $state(false);
  let audioEl: HTMLAudioElement | undefined = $state();
  let settingsOpen = $state(false);
  let idInput = $state('');

  async function loadLibrary(id: number) {
    busy = true;
    error = '';
    try {
      profile = await api.userProfile(id);
      playlists = (await api.userPlaylists(id)).data;
      try {
        liked = (await api.userTracks(id)).data;
      } catch {
        liked = [];
      }
      try {
        albums = (await api.userAlbums(id)).data;
      } catch {
        albums = [];
      }
      try {
        artists = (await api.userArtists(id)).data;
      } catch {
        artists = [];
      }
    } catch (e: any) {
      error = `Could not load profile ${id}. Make sure your Deezer profile is public. ${e?.message ?? ''}`;
      profile = null;
      playlists = [];
    } finally {
      busy = false;
    }
  }

  async function bootstrap() {
    await loadSettings();
    if ($userId) {
      await loadLibrary($userId);
      await openFlow();
    } else {
      settingsOpen = true;
      await openCharts();
    }
  }

  async function saveSettings() {
    const parsed = api.parseUserId(idInput);
    if (!parsed) {
      error = 'Enter numeric user ID or profile URL (deezer.com/profile/12345).';
      return;
    }
    await setUserId(parsed);
    settingsOpen = false;
    idInput = '';
    await loadLibrary(parsed);
  }

  async function clearProfile() {
    await setUserId(null);
    profile = null;
    playlists = [];
    albums = [];
    artists = [];
    liked = [];
    settingsOpen = true;
  }

  async function doSearch(e: Event) {
    e.preventDefault();
    if (!query.trim()) return;
    busy = true;
    error = '';
    try {
      const results = (await api.search(query)).data;
      activeTracks = results;
      activeTitle = `Search: ${query}`;
      lastSearchResults = results;
      lastSearchQuery = query;
    } catch (e: any) {
      error = e?.message ?? String(e);
    } finally {
      busy = false;
    }
  }

  function openSearch() {
    pushHistory();
    view = 'search';
    activeTracks = lastSearchResults;
    activeTitle = lastSearchQuery ? `Search: ${lastSearchQuery}` : 'Search';
    setTimeout(() => searchInput?.focus(), 0);
  }

  async function openPlaylist(p: api.Playlist) {
    pushHistory();
    view = 'playlist';
    busy = true;
    try {
      activeTracks = (await api.playlistTracks(p.id)).data;
      activeTitle = p.title;
    } catch (e: any) {
      error = e?.message ?? String(e);
    } finally {
      busy = false;
    }
  }

  async function openAlbum(a: api.Album) {
    pushHistory();
    view = 'album';
    busy = true;
    try {
      const tracks = (await api.albumTracks(a.id)).data;
      // album endpoint omits album field per track; inject parent album info
      activeTracks = tracks.map((t) => ({
        ...t,
        album: {
          id: a.id,
          title: a.title,
          cover_medium: a.cover_medium,
          cover_big: a.cover_big,
        },
        artist: t.artist?.id ? t.artist : { id: a.artist.id, name: a.artist.name, picture_small: '' },
      }));
      activeTitle = `${a.title} · ${a.artist.name}`;
    } catch (e: any) {
      error = e?.message ?? String(e);
    } finally {
      busy = false;
    }
  }

  let artistProfile = $state<api.Artist | null>(null);
  let artistAlbums = $state<api.Album[]>([]);
  let artistBio = $state<string>('');
  let topExpanded = $state(false);
  const TOP_COLLAPSED_COUNT = 5;

  type Snapshot = {
    view: View;
    activeTracks: api.Track[];
    activeTitle: string;
    artistProfile: api.Artist | null;
    artistAlbums: api.Album[];
    artistBio: string;
    topExpanded: boolean;
  };
  let history = $state<Snapshot[]>([]);
  function snapshot(): Snapshot {
    return {
      view,
      activeTracks,
      activeTitle,
      artistProfile,
      artistAlbums,
      artistBio,
      topExpanded,
    };
  }
  function pushHistory() {
    if (activeTitle) history.push(snapshot());
  }
  function goBack() {
    const s = history.pop();
    if (!s) return;
    view = s.view;
    activeTracks = s.activeTracks;
    activeTitle = s.activeTitle;
    artistProfile = s.artistProfile;
    artistAlbums = s.artistAlbums;
    artistBio = s.artistBio;
    topExpanded = s.topExpanded;
  }

  async function fetchLastFmBio(name: string): Promise<string> {
    const key = import.meta.env.VITE_LASTFM_KEY;
    if (!key) return '';
    try {
      const url = `https://ws.audioscrobbler.com/2.0/?method=artist.getinfo&artist=${encodeURIComponent(name)}&api_key=${key}&format=json`;
      const res = await fetch(url);
      if (!res.ok) return '';
      const j = await res.json();
      const raw: string = j?.artist?.bio?.content ?? j?.artist?.bio?.summary ?? '';
      // strip "<a href=\"...\">Read more on Last.fm</a>" and html tags, normalize whitespace
      return raw
        .replace(/<a[^>]*>.*?<\/a>/g, '')
        .replace(/<[^>]+>/g, '')
        .replace(/\s+/g, ' ')
        .trim();
    } catch {
      return '';
    }
  }

  async function fetchSummary(title: string): Promise<{ extract: string; type: string } | null> {
    try {
      const url = `https://en.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(title)}`;
      const res = await fetch(url, { headers: { Accept: 'application/json' } });
      if (!res.ok) return null;
      const j = await res.json();
      return { extract: j.extract ?? '', type: j.type ?? '' };
    } catch {
      return null;
    }
  }

  async function fetchBio(name: string): Promise<string> {
    const lfm = await fetchLastFmBio(name);
    if (lfm) return lfm;
    // try common Wikipedia disambiguation suffixes for music artists first,
    // since the plain name often hits a disambiguation page.
    const candidates = [
      `${name} (band)`,
      `${name} (musician)`,
      `${name} (rapper)`,
      `${name} (singer)`,
      name,
    ];
    for (const c of candidates) {
      const r = await fetchSummary(c);
      if (r && r.type !== 'disambiguation' && r.extract) return r.extract;
    }
    // last resort: search and pick top hit that isn't a disambiguation.
    try {
      const sUrl = `https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(name + ' band musician')}&format=json&origin=*`;
      const sRes = await fetch(sUrl);
      if (sRes.ok) {
        const sJ = await sRes.json();
        const hits: { title: string }[] = sJ.query?.search ?? [];
        for (const h of hits.slice(0, 3)) {
          const r = await fetchSummary(h.title);
          if (r && r.type !== 'disambiguation' && r.extract) return r.extract;
        }
      }
    } catch {
      /* ignore */
    }
    return '';
  }

  async function openArtist(a: api.Artist) {
    pushHistory();
    view = 'artist';
    busy = true;
    artistProfile = a;
    artistAlbums = [];
    artistBio = '';
    activeTracks = [];
    topExpanded = false;
    activeTitle = a.name;
    try {
      const [info, top, albs] = await Promise.all([
        api.artistInfo(a.id),
        api.artistTop(a.id),
        api.artistAlbums(a.id),
      ]);
      artistProfile = info;
      artistAlbums = albs.data;
      activeTracks = top.data;
      fetchBio(a.name).then((b) => (artistBio = b));
    } catch (e: any) {
      error = e?.message ?? String(e);
    } finally {
      busy = false;
    }
  }

  function openLiked() {
    pushHistory();
    view = 'liked';
    activeTracks = liked;
    activeTitle = 'Liked tracks';
  }

  const FLOW_TTL_MS = 6 * 60 * 60 * 1000;
  function flowCacheKey(id: number) {
    return `dzr:flow:${id}`;
  }

  async function openFlow(force = false) {
    if (!$userId) return;
    pushHistory();
    view = 'flow';
    const key = flowCacheKey($userId);
    if (!force) {
      try {
        const raw = localStorage.getItem(key);
        if (raw) {
          const cached = JSON.parse(raw) as { tracks: api.Track[]; at: number };
          if (Date.now() - cached.at < FLOW_TTL_MS && cached.tracks?.length) {
            activeTracks = cached.tracks;
            activeTitle = 'Flow';
            return;
          }
        }
      } catch {
        /* ignore */
      }
    }
    busy = true;
    try {
      const tracks = (await api.userFlow($userId)).data;
      activeTracks = tracks;
      activeTitle = 'Flow';
      try {
        localStorage.setItem(key, JSON.stringify({ tracks, at: Date.now() }));
      } catch {
        /* quota */
      }
    } catch (e: any) {
      error = e?.message ?? String(e);
    } finally {
      busy = false;
    }
  }

  async function openCharts() {
    pushHistory();
    view = 'charts';
    busy = true;
    try {
      activeTracks = (await api.chartTracks()).data;
      activeTitle = 'Charts';
    } catch (e: any) {
      error = e?.message ?? String(e);
    } finally {
      busy = false;
    }
  }

  function playAll() {
    if (activeTracks.length) playQueue(activeTracks, 0);
  }

  function shuffleAll() {
    if (!activeTracks.length) return;
    const a = activeTracks.slice();
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [a[i], a[j]] = [a[j], a[i]];
    }
    playQueue(a, 0);
  }

  function fmt(s: number) {
    if (!Number.isFinite(s)) return '0:00';
    const m = Math.floor(s / 60);
    const r = Math.floor(s % 60);
    return `${m}:${r.toString().padStart(2, '0')}`;
  }

  $effect(() => {
    if (audioEl) attachAudio(audioEl);
  });

  $effect(() => {
    if (activeTracks.length) prefetch(activeTracks);
  });

  onMount(bootstrap);
</script>

<audio bind:this={audioEl} preload="auto"></audio>

<div class="app">
  <aside class="sidebar">
    <header>
      {#if profile}
        <img src={profile.picture_medium} alt={profile.name} />
        <span>{profile.name}</span>
        <button class="link" onclick={clearProfile}>Change</button>
      {:else}
        <button onclick={() => (settingsOpen = true)}>Set Deezer profile</button>
      {/if}
    </header>
    <nav>
      <button class="navbtn" class:active={view === 'search'} onclick={openSearch}>
        {@html searchSvg}<span>Search</span>
      </button>
      {#if liked.length}
        <button class="navbtn" class:active={view === 'liked'} onclick={openLiked}>
          {@html heartSvg}<span>Liked ({liked.length})</span>
        </button>
      {/if}
      {#if $userId}
        <button class="navbtn" class:active={view === 'flow'} onclick={() => openFlow()}>
          {@html flowSvg}<span>Flow</span>
        </button>
      {/if}
      <button class="navbtn" class:active={view === 'charts'} onclick={openCharts}>
        {@html chartsSvg}<span>Charts</span>
      </button>
    </nav>
    {#if playlists.length || albums.length || artists.length}
      <div class="libtabs">
        <button class:on={libraryTab === 'playlists'} onclick={() => (libraryTab = 'playlists')}>Playlists</button>
        <button class:on={libraryTab === 'artists'} onclick={() => (libraryTab = 'artists')}>Artists</button>
        <button class:on={libraryTab === 'albums'} onclick={() => (libraryTab = 'albums')}>Albums</button>
      </div>
      <ul class="playlists">
        {#if libraryTab === 'playlists'}
          {#each playlists as p (p.id)}
            <li>
              <button onclick={() => openPlaylist(p)}>
                <img src={p.picture_medium} alt="" />
                <div>
                  <div class="title">{p.title}</div>
                  <div class="sub">{p.nb_tracks} tracks</div>
                </div>
              </button>
            </li>
          {/each}
        {:else if libraryTab === 'artists'}
          {#each artists as a (a.id)}
            <li>
              <button onclick={() => openArtist(a)}>
                <img src={a.picture_medium} alt="" />
                <div>
                  <div class="title">{a.name}</div>
                  <div class="sub">{a.nb_album} albums</div>
                </div>
              </button>
            </li>
          {/each}
        {:else}
          {#each albums as a (a.id)}
            <li>
              <button onclick={() => openAlbum(a)}>
                <img src={a.cover_medium} alt="" />
                <div>
                  <div class="title">{a.title}</div>
                  <div class="sub">{a.artist.name}</div>
                </div>
              </button>
            </li>
          {/each}
        {/if}
      </ul>
    {/if}
  </aside>

  <main>
    {#if view === 'search'}
      <form onsubmit={doSearch} class="search">
        <input bind:this={searchInput} bind:value={query} placeholder="Search Deezer…" />
        <button type="submit" disabled={busy}>Search</button>
      </form>
    {/if}

    <div class="list-head">
      {#if history.length}
        <button class="back" onclick={goBack} title="Back">
          ‹ {history[history.length - 1].activeTitle}
        </button>
      {/if}
      <h2 class="page-title">{@html viewIcon(view)}<span>{activeTitle}</span></h2>
      {#if activeTracks.length}
        <div class="list-actions">
          <button class="action play" onclick={playAll} title="Play all">▶ Play</button>
          <button class="action" onclick={shuffleAll} title="Shuffle play" aria-label="Shuffle play">
            {@html shuffleSvg}<span>Shuffle</span>
          </button>
          {#if view === 'flow'}
            <button class="action" onclick={() => openFlow(true)} title="Refresh Flow" aria-label="Refresh Flow">
              {@html refreshSvg}<span>Refresh</span>
            </button>
          {/if}
        </div>
      {/if}
    </div>

    {#if error}<div class="err">{error}</div>{/if}

    {#if view === 'artist' && artistProfile}
      <div class="artist-hero">
        <img src={artistProfile.picture_medium} alt={artistProfile.name} />
        <div class="hero-meta">
          <h1>{artistProfile.name}</h1>
          <div class="hero-stats">
            {artistProfile.nb_fan?.toLocaleString?.() ?? artistProfile.nb_fan} fans · {artistProfile.nb_album} albums
          </div>
          {#if artistBio}
            <p class="bio">{artistBio}</p>
          {/if}
        </div>
      </div>
      <h3 class="section-h">Top tracks</h3>
    {/if}

    <ul class="tracks">
      {#each (view === 'artist' && !topExpanded ? activeTracks.slice(0, TOP_COLLAPSED_COUNT) : activeTracks) as t, i (t.id)}
        {@const res = $resolutions.get(t.id)}
        {@const failed = res?.status === 'failed'}
        {@const resolving = res?.status === 'resolving'}
        <li class:current={$player.current?.id === t.id} class:failed>
          <button
            class="play-target"
            onclick={() => !failed && playQueue(activeTracks, i)}
            disabled={failed}
            title={failed ? res?.error : 'Play'}
          >
            <img src={t.album.cover_medium} alt="" />
            <span class="title">{t.title}</span>
          </button>
          <div class="sub">
            {#if t.artist?.id}
              <button
                class="linkbtn"
                onclick={() => openArtist({ id: t.artist.id, name: t.artist.name, picture_medium: '', nb_album: 0, nb_fan: 0 })}
              >{t.artist.name}</button>
            {:else}
              <span>{t.artist.name}</span>
            {/if}
            <span class="dot">·</span>
            {#if t.album?.id}
              <button
                class="linkbtn"
                onclick={() => openAlbum({ id: t.album.id, title: t.album.title, cover_medium: t.album.cover_medium, cover_big: t.album.cover_big ?? '', artist: { id: t.artist.id ?? 0, name: t.artist.name ?? '' }, nb_tracks: 0 })}
              >{t.album.title}</button>
            {:else}
              <span>{t.album.title}</span>
            {/if}
          </div>
          <div class="status">
            {#if resolving}
              <span class="spinner" aria-label="resolving"></span>
            {:else if failed}
              <span class="x" title={res?.error}>✕</span>
            {/if}
          </div>
          <div class="dur">{fmt(t.duration)}</div>
        </li>
      {/each}
    </ul>

    {#if view === 'artist' && activeTracks.length > TOP_COLLAPSED_COUNT}
      <button class="expand" onclick={() => (topExpanded = !topExpanded)}>
        {topExpanded ? 'Show less' : `Show all ${activeTracks.length}`}
      </button>
    {/if}

    {#if view === 'artist' && artistAlbums.length}
      <h3 class="section-h">Albums</h3>
      <div class="album-grid">
        {#each artistAlbums as a (a.id)}
          <button class="album-card" onclick={() => openAlbum({ ...a, artist: a.artist?.id ? a.artist : { id: artistProfile?.id ?? 0, name: artistProfile?.name ?? '' } })}>
            <img src={a.cover_medium} alt={a.title} />
            <div class="album-title">{a.title}</div>
          </button>
        {/each}
      </div>
    {/if}
  </main>

  <footer class="now">
    {#if $player.current?.album.cover_medium}
      <img src={$player.current.album.cover_medium} alt="" />
    {:else}
      <div class="placeholder"></div>
    {/if}
    <div class="meta">
      <div class="title">
        {$player.current?.title ?? '—'}
        {#if $player.current && $resolutions.get($player.current.id)?.status === 'resolving'}
          <span class="loading">resolving…</span>
        {/if}
      </div>
      <div class="sub">{$player.current?.artist.name ?? ''}</div>
    </div>
    <div class="controls">
      <button
        class="icon-btn"
        class:active={$player.shuffle}
        onclick={toggleShuffle}
        title="Shuffle"
        aria-label="Shuffle"
        aria-pressed={$player.shuffle}
      >
        {@html shuffleSvg}
      </button>
      <button onclick={prev} disabled={!$player.current}>⏮</button>
      {#if $player.playing}
        <button onclick={pause}>⏸</button>
      {:else}
        <button onclick={resume} disabled={!$player.current}>▶</button>
      {/if}
      <button onclick={next} disabled={!$player.current}>⏭</button>
    </div>
    {#if true}
      {@const dur = $player.current?.duration ?? $player.duration ?? 0}
      <div class="time">
        <span>{fmt(Math.min($player.position, dur))}</span>
        <input
          type="range"
          min="0"
          max={dur}
          step="1"
          value={Math.min($player.position, dur)}
          style="--val: {dur ? (Math.min($player.position, dur) / dur) * 100 : 0}"
          oninput={(e) => seek(Number((e.target as HTMLInputElement).value))}
        />
        <span>{fmt(dur)}</span>
      </div>
    {/if}
  </footer>

  {#if settingsOpen}
    <div class="modal-backdrop" role="presentation" onclick={() => (settingsOpen = false)}>
      <div class="modal" role="dialog" aria-modal="true" tabindex="-1" onclick={(e) => e.stopPropagation()} onkeydown={(e) => e.key === 'Escape' && (settingsOpen = false)}>
        <h3>Connect your Deezer profile</h3>
        <p>
          Paste your Deezer profile URL (e.g.
          <code>deezer.com/profile/12345</code>) or your numeric user ID.
        </p>
        <p class="hint">
          Profile must be set to <strong>Public</strong> in Deezer account settings for playlists/likes to load.
        </p>
        <input
          bind:value={idInput}
          placeholder="https://deezer.com/profile/12345 or 12345"
          onkeydown={(e) => e.key === 'Enter' && saveSettings()}
        />
        <div class="actions">
          <button onclick={() => (settingsOpen = false)}>Cancel</button>
          <button class="primary" onclick={saveSettings}>Save</button>
        </div>
      </div>
    </div>
  {/if}
</div>

<style>
  :global(html, body) { margin: 0; height: 100%; background: #121212; color: #eee; font-family: Inter, system-ui, sans-serif; overflow: hidden; }
  .app { display: grid; grid-template-columns: 280px 1fr; grid-template-rows: 1fr 88px; grid-template-areas: "side main" "now now"; height: 100vh; }
  .sidebar { grid-area: side; background: #0a0a0a; padding: 16px; overflow-y: auto; }
  .sidebar header { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
  .sidebar header img { width: 32px; height: 32px; border-radius: 50%; }
  .link { background: transparent; color: #888; border: 0; cursor: pointer; font-size: 12px; padding: 0; }
  nav { display: flex; flex-direction: column; gap: 4px; margin-bottom: 24px; }
  .navbtn { background: transparent; color: #ddd; border: 0; text-align: left; padding: 8px 10px; border-radius: 6px; cursor: pointer; font-size: 14px; display: inline-flex; align-items: center; gap: 10px; }
  .navbtn:hover { background: #1f1f1f; }
  .navbtn.active { background: #1f1f1f; color: #fff; }
  :global(.navbtn svg) { flex-shrink: 0; }
  main { grid-area: main; padding: 16px 24px; overflow-y: auto; }
  main h2 { margin: 0; font-size: 20px; }
  .page-title { display: inline-flex; align-items: center; gap: 10px; }
  :global(.page-title svg) { width: 20px; height: 20px; color: #888; }
  .list-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 16px 0 12px; flex-wrap: wrap; }
  .back { background: transparent; border: 0; color: #888; cursor: pointer; padding: 6px 4px; font-size: 13px; max-width: 30ch; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .back:hover { color: #fff; }
  .list-actions { display: flex; gap: 8px; }
  .action { padding: 8px 16px; border-radius: 20px; border: 1px solid #333; background: #1e1e1e; color: #eee; cursor: pointer; font-size: 13px; }
  .action:hover { background: #2a2a2a; }
  .action.play { background: #1db954; color: #000; border-color: #1db954; font-weight: 600; }
  .action.play:hover { background: #1ed760; }
  .action { display: inline-flex; align-items: center; gap: 6px; }
  :global(.action svg) { display: block; }
  .icon-btn { background: transparent !important; border: 0 !important; padding: 8px !important; color: #aaa !important; border-radius: 50% !important; display: inline-flex; align-items: center; justify-content: center; }
  .icon-btn:hover { color: #fff !important; }
  .icon-btn.active { color: #1db954 !important; }
  .now { grid-area: now; background: #181818; border-top: 1px solid #282828; display: grid; grid-template-columns: 64px 1fr auto 1.5fr; align-items: center; gap: 16px; padding: 12px 16px; }
  .now img, .placeholder { width: 56px; height: 56px; border-radius: 4px; background: #222; }
  .controls { display: flex; gap: 8px; }
  .controls button { background: #282828; color: #fff; border: 0; padding: 8px 14px; border-radius: 24px; cursor: pointer; font-size: 16px; }
  .controls button:disabled { opacity: 0.4; cursor: default; }
  .time { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #aaa; }
  .time input[type="range"] {
    flex: 1;
    -webkit-appearance: none;
    appearance: none;
    height: 4px;
    background: transparent;
    cursor: pointer;
    --dzr-purple: #a238ff;
    --dzr-gutter: #2a2a32;
    --pct: calc(var(--val, 0) * 1%);
  }
  .time input[type="range"]::-webkit-slider-runnable-track {
    height: 3px;
    border-radius: 2px;
    background: linear-gradient(
      to right,
      var(--dzr-purple) 0%,
      var(--dzr-purple) var(--pct),
      var(--dzr-gutter) var(--pct),
      var(--dzr-gutter) 100%
    );
  }
  .time input[type="range"]::-webkit-slider-thumb {
    -webkit-appearance: none;
    appearance: none;
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: var(--dzr-purple);
    margin-top: -3.5px;
    border: 0;
    box-shadow: 0 0 0 1px rgba(0,0,0,0.4);
  }
  .time input[type="range"]:hover::-webkit-slider-thumb { width: 12px; height: 12px; margin-top: -4.5px; }
  .search { display: flex; gap: 8px; margin-bottom: 16px; }
  .search input { flex: 1; padding: 10px 12px; border-radius: 24px; border: 1px solid #333; background: #1e1e1e; color: #fff; }
  .search button { padding: 10px 18px; border-radius: 24px; border: 0; background: #1db954; color: #000; font-weight: 600; cursor: pointer; }
  ul { list-style: none; padding: 0; margin: 0; }
  .tracks li {
    display: grid;
    grid-template-columns: minmax(220px, 1fr) minmax(140px, 1.5fr) 20px auto;
    gap: 12px;
    align-items: center;
    padding: 4px 8px;
    border-radius: 6px;
  }
  .tracks li.failed { opacity: 0.4; }
  .tracks li.current { background: #1f3a25; }
  .tracks li:hover { background: #1f1f1f; }
  .tracks li.current:hover { background: #244a30; }
  .play-target {
    display: grid;
    grid-template-columns: 48px 1fr;
    gap: 12px;
    align-items: center;
    background: transparent;
    border: 0;
    padding: 0;
    color: inherit;
    cursor: pointer;
    text-align: left;
    min-width: 0;
  }
  .play-target:disabled { cursor: not-allowed; }
  .play-target img { width: 48px; height: 48px; border-radius: 4px; }
  .play-target .title { font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .playlists li button { width: 100%; display: grid; grid-template-columns: 48px 1fr auto; gap: 12px; align-items: center; background: transparent; color: #eee; border: 0; padding: 6px 8px; text-align: left; cursor: pointer; border-radius: 6px; }
  .status { display: flex; align-items: center; justify-content: center; }
  .x { color: #c66; font-size: 14px; }
  .spinner { width: 12px; height: 12px; border: 2px solid #444; border-top-color: #1db954; border-radius: 50%; animation: spin 0.7s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
  .playlists li button:hover { background: #222; }
  .playlists img { width: 48px; height: 48px; border-radius: 4px; }
  .linkbtn { background: transparent; border: 0; padding: 0; color: #999; font-size: 13px; cursor: pointer; text-align: left; font-family: inherit; }
  .linkbtn:hover { color: #fff; text-decoration: underline; }
  .dot { color: #555; margin: 0 4px; }
  .title { font-weight: 500; }
  .sub, .dur { color: #999; font-size: 13px; }
  .err { background: #4a1d1d; color: #fbb; padding: 8px 12px; border-radius: 4px; margin-bottom: 12px; }
  .meta .title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 40ch; display: flex; align-items: center; gap: 8px; }
  .loading { color: #888; font-size: 11px; font-weight: 400; }
  h3 { color: #aaa; font-size: 12px; text-transform: uppercase; letter-spacing: 0.1em; }
  .libtabs { display: flex; gap: 4px; margin: 16px 0 8px; }
  .libtabs button { flex: 1; background: transparent; color: #888; border: 0; padding: 6px 4px; border-radius: 4px; cursor: pointer; font-size: 11px; text-transform: uppercase; letter-spacing: 0.05em; }
  .libtabs button:hover { color: #fff; }
  .libtabs button.on { color: #fff; background: #1f1f1f; }
  .artist-hero { display: flex; gap: 24px; align-items: flex-start; margin: 8px 0 24px; }
  .artist-hero img { width: 160px; height: 160px; border-radius: 50%; object-fit: cover; flex-shrink: 0; }
  .hero-meta { flex: 1; min-width: 0; }
  .hero-meta h1 { margin: 0 0 4px; font-size: 32px; }
  .hero-stats { color: #888; font-size: 13px; margin-bottom: 12px; }
  .bio { color: #bbb; font-size: 13px; line-height: 1.5; margin: 0; max-height: 5.5em; overflow: hidden; text-overflow: ellipsis; }
  .section-h { color: #aaa; font-size: 12px; text-transform: uppercase; letter-spacing: 0.1em; margin: 24px 0 8px; }
  .album-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 16px; padding: 0; }
  .album-card { background: transparent; border: 0; padding: 0; cursor: pointer; text-align: left; color: #eee; }
  .album-card img { width: 100%; aspect-ratio: 1; border-radius: 6px; object-fit: cover; }
  .album-card:hover img { filter: brightness(1.1); }
  .album-title { font-size: 13px; margin-top: 8px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .expand { background: transparent; border: 0; color: #999; font-size: 12px; cursor: pointer; padding: 8px 12px; margin-top: 4px; text-transform: uppercase; letter-spacing: 0.05em; font-weight: 600; }
  .expand:hover { color: #fff; }
  .modal-backdrop { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: grid; place-items: center; z-index: 100; }
  .modal { background: #1c1c1c; border: 1px solid #333; padding: 24px; border-radius: 8px; width: min(480px, 90vw); }
  .modal h3 { font-size: 16px; color: #fff; text-transform: none; letter-spacing: normal; margin: 0 0 12px; }
  .modal p { color: #bbb; font-size: 14px; line-height: 1.5; }
  .modal p.hint { color: #888; font-size: 12px; }
  .modal code { background: #222; padding: 1px 5px; border-radius: 3px; }
  .modal input { width: 100%; box-sizing: border-box; margin: 12px 0; padding: 10px 12px; border-radius: 6px; border: 1px solid #333; background: #0f0f0f; color: #fff; }
  .modal .actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px; }
  .modal .actions button { padding: 8px 16px; border-radius: 6px; border: 0; cursor: pointer; background: #2a2a2a; color: #fff; }
  .modal .actions button.primary { background: #1db954; color: #000; font-weight: 600; }
</style>
