import { invoke } from '@tauri-apps/api/core';
import { writable, get } from 'svelte/store';
import type { Track } from './deezer';

export type Resolved = {
  url: string;
  video_id: string;
  duration: number | null;
  title: string | null;
};

export type ResStatus = 'resolving' | 'resolved' | 'failed';
export type ResEntry = { status: ResStatus; url?: string; error?: string };

export type PlayerState = {
  queue: Track[];
  index: number;
  current: Track | null;
  playing: boolean;
  position: number;
  duration: number;
  error: string;
  shuffle: boolean;
};

const initial: PlayerState = {
  queue: [],
  index: -1,
  current: null,
  playing: false,
  position: 0,
  duration: 0,
  error: '',
  shuffle: false,
};

export const player = writable<PlayerState>(initial);
export const resolutions = writable<Map<number, ResEntry>>(new Map());

let audio: HTMLAudioElement | null = null;
let pendingPlayId: number | null = null;
let transitioning = false;

const MAX_CONCURRENT = 3;
let inFlight = 0;
const queue: Track[] = [];
const promises = new Map<number, Promise<Resolved>>();
let generation = 0;

function setRes(id: number, e: ResEntry) {
  resolutions.update((m) => {
    const next = new Map(m);
    next.set(id, e);
    return next;
  });
}

function resolveTrack(t: Track): Promise<Resolved> {
  const existing = promises.get(t.id);
  if (existing) return existing;
  const p = invoke<Resolved>('resolve_track', {
    trackId: t.id,
    artist: t.artist.name,
    title: t.title,
    duration: t.duration,
  });
  promises.set(t.id, p);
  setRes(t.id, { status: 'resolving' });
  p.then(
    (r) => setRes(t.id, { status: 'resolved', url: r.url }),
    (e) => {
      promises.delete(t.id);
      setRes(t.id, { status: 'failed', error: e?.message ?? String(e) });
    },
  );
  return p;
}

function pump() {
  while (inFlight < MAX_CONCURRENT && queue.length > 0) {
    const t = queue.shift()!;
    if (promises.has(t.id)) continue;
    inFlight++;
    resolveTrack(t).finally(() => {
      inFlight--;
      pump();
    });
  }
}

export function prefetch(tracks: Track[]) {
  const gen = ++generation;
  queue.length = 0;
  for (const t of tracks) {
    if (promises.has(t.id)) continue;
    queue.push(t);
  }
  if (gen === generation) pump();
}

let userWantsPlay = false;
let endedForTrackId: number | null = null;

export function attachAudio(el: HTMLAudioElement) {
  audio = el;
  el.addEventListener('play', () => {
    // WebKit / OS sometimes calls play() on window focus or media-session
    // restore. Only stay playing if user explicitly asked for it.
    if (!userWantsPlay) {
      el.pause();
      return;
    }
    player.update((s) => ({ ...s, playing: true }));
    if ('mediaSession' in navigator) {
      navigator.mediaSession.playbackState = 'playing';
    }
  });
  el.addEventListener('pause', () => {
    player.update((s) => ({ ...s, playing: false }));
    if ('mediaSession' in navigator) {
      navigator.mediaSession.playbackState = 'paused';
    }
  });
  el.addEventListener('timeupdate', () => {
    player.update((s) => ({ ...s, position: el.currentTime }));
    // YouTube DASH m4a often reports inflated `audio.duration` (mvhd) so the
    // native 'ended' event never fires. Use Deezer's `track.duration` as
    // authoritative and advance manually.
    const st = get(player);
    const trackDur = st.current?.duration ?? 0;
    if (
      st.current &&
      trackDur > 0 &&
      el.currentTime >= trackDur - 0.4 &&
      endedForTrackId !== st.current.id
    ) {
      endedForTrackId = st.current.id;
      next();
    }
  });
  el.addEventListener('loadedmetadata', () =>
    player.update((s) => ({ ...s, duration: el.duration || 0 })),
  );
  el.addEventListener('ended', () => {
    const st = get(player);
    if (st.current && endedForTrackId === st.current.id) return; // already advanced
    if (st.current) endedForTrackId = st.current.id;
    next();
  });
  el.addEventListener('error', () => {
    if (transitioning) return; // ignore spurious errors during src swap
    if (!el.error || el.error.code === 3) return; // no source = transient
    const st = get(player);
    if (!st.current) return;
    promises.delete(st.current.id);
    setRes(st.current.id, { status: 'failed', error: 'playback failed' });
    next();
  });

  if ('mediaSession' in navigator) {
    navigator.mediaSession.setActionHandler('play', () => {
      userWantsPlay = true;
      el.play().catch(() => {});
    });
    navigator.mediaSession.setActionHandler('pause', () => {
      userWantsPlay = false;
      el.pause();
    });
    navigator.mediaSession.setActionHandler('nexttrack', () => next());
    navigator.mediaSession.setActionHandler('previoustrack', () => prev());
  }
}

async function loadIndex(idx: number) {
  const st = get(player);
  if (idx < 0 || idx >= st.queue.length) return;
  const track = st.queue[idx];
  pendingPlayId = track.id;
  transitioning = true;
  if (audio) {
    audio.pause();
    audio.removeAttribute('src');
    audio.load();
  }
  player.update((s) => ({
    ...s,
    index: idx,
    current: track,
    playing: false,
    error: '',
    position: 0,
    duration: 0,
  }));
  try {
    const resolved = await resolveTrack(track);
    if (pendingPlayId !== track.id) {
      transitioning = false;
      return;
    }
    if (audio) {
      audio.src = resolved.url;
      userWantsPlay = true;
      transitioning = false;
      audio.play().catch((e) => {
        player.update((s) => ({ ...s, error: e?.message ?? String(e) }));
      });
    }
    if ('mediaSession' in navigator) {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: track.title,
        artist: track.artist.name,
        album: track.album.title,
        artwork: track.album.cover_medium
          ? [{ src: track.album.cover_medium }]
          : [],
      });
    }
  } catch (e: any) {
    transitioning = false;
    if (pendingPlayId !== track.id) return;
    setRes(track.id, { status: 'failed', error: e?.message ?? String(e) });
    next();
  }
}

export function playQueue(tracks: Track[], startIdx = 0) {
  player.update((s) => ({ ...s, queue: tracks, index: -1, current: null }));
  loadIndex(startIdx);
}

export function pause() {
  userWantsPlay = false;
  audio?.pause();
}
export function resume() {
  userWantsPlay = true;
  audio?.play();
}
export function togglePlay() {
  if (!audio) return;
  if (audio.paused) {
    userWantsPlay = true;
    audio.play();
  } else {
    userWantsPlay = false;
    audio.pause();
  }
}
export function seek(positionSec: number) {
  if (audio && Number.isFinite(positionSec)) audio.currentTime = positionSec;
}
export function next() {
  const st = get(player);
  if (st.queue.length === 0) return;
  // skip past failed tracks so autoplay marches down the list
  const resmap = get(resolutions);
  const skip = (i: number): number => {
    let n = i;
    let guard = 0;
    while (n < st.queue.length && guard < st.queue.length) {
      const id = st.queue[n].id;
      if (resmap.get(id)?.status !== 'failed') return n;
      n++;
      guard++;
    }
    return st.queue.length;
  };
  if (st.shuffle && st.queue.length > 1) {
    let idx = Math.floor(Math.random() * st.queue.length);
    if (idx === st.index) idx = (idx + 1) % st.queue.length;
    loadIndex(idx);
    return;
  }
  const target = skip(st.index + 1);
  if (target < st.queue.length) loadIndex(target);
}

export function toggleShuffle() {
  player.update((s) => ({ ...s, shuffle: !s.shuffle }));
}
export function prev() {
  const st = get(player);
  if (audio && audio.currentTime > 3) {
    audio.currentTime = 0;
    return;
  }
  if (st.index > 0) loadIndex(st.index - 1);
}
export function setVolume(v: number) {
  if (audio) audio.volume = Math.max(0, Math.min(1, v));
}
