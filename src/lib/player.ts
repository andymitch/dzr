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

export function attachAudio(el: HTMLAudioElement) {
  audio = el;
  el.addEventListener('play', () => player.update((s) => ({ ...s, playing: true })));
  el.addEventListener('pause', () => player.update((s) => ({ ...s, playing: false })));
  el.addEventListener('timeupdate', () =>
    player.update((s) => ({ ...s, position: el.currentTime })),
  );
  el.addEventListener('loadedmetadata', () =>
    player.update((s) => ({ ...s, duration: el.duration || 0 })),
  );
  el.addEventListener('ended', () => next());
  el.addEventListener('error', async () => {
    const st = get(player);
    if (!st.current) return;
    promises.delete(st.current.id);
    try {
      await invoke('resolver_invalidate', { trackId: st.current.id });
      await loadIndex(st.index);
    } catch (e: any) {
      player.update((s) => ({ ...s, error: e?.message ?? String(e) }));
    }
  });
}

async function loadIndex(idx: number) {
  const st = get(player);
  if (idx < 0 || idx >= st.queue.length) return;
  const track = st.queue[idx];
  pendingPlayId = track.id;
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
    if (pendingPlayId !== track.id) return;
    if (audio) {
      audio.src = resolved.url;
      audio.play().catch((e) => {
        player.update((s) => ({ ...s, error: e?.message ?? String(e) }));
      });
    }
  } catch (e: any) {
    if (pendingPlayId !== track.id) return;
    player.update((s) => ({ ...s, error: e?.message ?? String(e) }));
  }
}

export function playQueue(tracks: Track[], startIdx = 0) {
  player.update((s) => ({ ...s, queue: tracks, index: -1, current: null }));
  loadIndex(startIdx);
}

export function pause() {
  audio?.pause();
}
export function resume() {
  audio?.play();
}
export function togglePlay() {
  if (!audio) return;
  if (audio.paused) audio.play();
  else audio.pause();
}
export function seek(positionSec: number) {
  if (audio && Number.isFinite(positionSec)) audio.currentTime = positionSec;
}
export function next() {
  const st = get(player);
  if (st.queue.length === 0) return;
  if (st.shuffle && st.queue.length > 1) {
    let idx = Math.floor(Math.random() * st.queue.length);
    if (idx === st.index) idx = (idx + 1) % st.queue.length;
    loadIndex(idx);
    return;
  }
  if (st.index + 1 < st.queue.length) loadIndex(st.index + 1);
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
