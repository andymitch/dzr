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
  import { shuffleSvg } from '$lib/icons';
  import { loadSettings, setUserId, userId } from '$lib/settings';

  let query = $state('');
  let activeTracks = $state<api.Track[]>([]);
  let activeTitle = $state('Search');
  let playlists = $state<api.Playlist[]>([]);
  let liked = $state<api.Track[]>([]);
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
    if ($userId) await loadLibrary($userId);
    else settingsOpen = true;
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
    liked = [];
    settingsOpen = true;
  }

  async function doSearch(e: Event) {
    e.preventDefault();
    if (!query.trim()) return;
    busy = true;
    error = '';
    try {
      activeTracks = (await api.search(query)).data;
      activeTitle = `Search: ${query}`;
    } catch (e: any) {
      error = e?.message ?? String(e);
    } finally {
      busy = false;
    }
  }

  async function openPlaylist(p: api.Playlist) {
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

  function openLiked() {
    activeTracks = liked;
    activeTitle = 'Liked tracks';
  }

  async function openFlow() {
    if (!$userId) return;
    busy = true;
    try {
      activeTracks = (await api.userFlow($userId)).data;
      activeTitle = 'Flow';
    } catch (e: any) {
      error = e?.message ?? String(e);
    } finally {
      busy = false;
    }
  }

  async function openCharts() {
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
      {#if $userId}
        <button class="navbtn" onclick={openFlow}>Flow</button>
      {/if}
      <button class="navbtn" onclick={openCharts}>Charts</button>
      {#if liked.length}
        <button class="navbtn" onclick={openLiked}>Liked ({liked.length})</button>
      {/if}
    </nav>
    {#if playlists.length}
      <h3>Playlists</h3>
      <ul class="playlists">
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
      </ul>
    {/if}
  </aside>

  <main>
    <form onsubmit={doSearch} class="search">
      <input bind:value={query} placeholder="Search Deezer…" />
      <button type="submit" disabled={busy}>Search</button>
    </form>

    <div class="list-head">
      <h2>{activeTitle}</h2>
      {#if activeTracks.length}
        <div class="list-actions">
          <button class="action play" onclick={playAll} title="Play all">▶ Play</button>
          <button class="action" onclick={shuffleAll} title="Shuffle play" aria-label="Shuffle play">
            {@html shuffleSvg}<span>Shuffle</span>
          </button>
        </div>
      {/if}
    </div>

    {#if error}<div class="err">{error}</div>{/if}

    <ul class="tracks">
      {#each activeTracks as t, i (t.id)}
        {@const res = $resolutions.get(t.id)}
        {@const failed = res?.status === 'failed'}
        {@const resolving = res?.status === 'resolving'}
        <li class:current={$player.current?.id === t.id} class:failed>
          <button
            onclick={() => !failed && playQueue(activeTracks, i)}
            disabled={failed}
            title={failed ? res?.error : ''}
          >
            <img src={t.album.cover_medium} alt="" />
            <div class="meta">
              <div class="title">{t.title}</div>
              <div class="sub">{t.artist.name} · {t.album.title}</div>
            </div>
            <div class="status">
              {#if resolving}
                <span class="spinner" aria-label="resolving"></span>
              {:else if failed}
                <span class="x" title={res?.error}>✕</span>
              {/if}
            </div>
            <div class="dur">{fmt(t.duration)}</div>
          </button>
        </li>
      {/each}
    </ul>
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
  .navbtn { background: transparent; color: #ddd; border: 0; text-align: left; padding: 8px 10px; border-radius: 6px; cursor: pointer; font-size: 14px; }
  .navbtn:hover { background: #1f1f1f; }
  main { grid-area: main; padding: 16px 24px; overflow-y: auto; }
  main h2 { margin: 0; font-size: 20px; }
  .list-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin: 16px 0 12px; }
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
  .time input { flex: 1; }
  .search { display: flex; gap: 8px; margin-bottom: 16px; }
  .search input { flex: 1; padding: 10px 12px; border-radius: 24px; border: 1px solid #333; background: #1e1e1e; color: #fff; }
  .search button { padding: 10px 18px; border-radius: 24px; border: 0; background: #1db954; color: #000; font-weight: 600; cursor: pointer; }
  ul { list-style: none; padding: 0; margin: 0; }
  .tracks li button { width: 100%; display: grid; grid-template-columns: 48px 1fr 20px auto; gap: 12px; align-items: center; background: transparent; color: #eee; border: 0; padding: 6px 8px; text-align: left; cursor: pointer; border-radius: 6px; }
  .playlists li button { width: 100%; display: grid; grid-template-columns: 48px 1fr auto; gap: 12px; align-items: center; background: transparent; color: #eee; border: 0; padding: 6px 8px; text-align: left; cursor: pointer; border-radius: 6px; }
  .tracks li.failed button { opacity: 0.4; cursor: not-allowed; }
  .status { display: flex; align-items: center; justify-content: center; }
  .x { color: #c66; font-size: 14px; }
  .spinner { width: 12px; height: 12px; border: 2px solid #444; border-top-color: #1db954; border-radius: 50%; animation: spin 0.7s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
  .tracks li.current button { background: #1f3a25; }
  .tracks li button:hover, .playlists li button:hover { background: #222; }
  .tracks img, .playlists img { width: 48px; height: 48px; border-radius: 4px; }
  .title { font-weight: 500; }
  .sub, .dur { color: #999; font-size: 13px; }
  .err { background: #4a1d1d; color: #fbb; padding: 8px 12px; border-radius: 4px; margin-bottom: 12px; }
  .meta .title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 40ch; display: flex; align-items: center; gap: 8px; }
  .loading { color: #888; font-size: 11px; font-weight: 400; }
  h3 { color: #aaa; font-size: 12px; text-transform: uppercase; letter-spacing: 0.1em; }
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
