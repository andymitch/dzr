import { invoke } from '@tauri-apps/api/core';

export type Track = {
  id: number;
  title: string;
  duration: number;
  preview: string;
  artist: { id: number; name: string; picture_small: string };
  album: { id: number; title: string; cover_medium: string; cover_big: string };
};

export type Playlist = {
  id: number;
  title: string;
  nb_tracks: number;
  picture_medium: string;
};

export type Album = {
  id: number;
  title: string;
  cover_medium: string;
  cover_big: string;
  artist: { id: number; name: string };
  nb_tracks: number;
};

export type Artist = {
  id: number;
  name: string;
  picture_medium: string;
  nb_album: number;
  nb_fan: number;
};

export type User = {
  id: number;
  name: string;
  picture_medium: string;
  link: string;
};

export type Paged<T> = { data: T[]; total: number; next?: string };

export type Resolved = {
  url: string;
  video_id: string;
  duration: number | null;
  title: string | null;
};

export const search = (q: string) => invoke<Paged<Track>>('deezer_search', { q });
export const userProfile = (id: number) => invoke<User>('deezer_user', { id });
export const userPlaylists = (id: number) =>
  invoke<Paged<Playlist>>('deezer_user_playlists', { id });
export const userTracks = (id: number) => invoke<Paged<Track>>('deezer_user_tracks', { id });
export const userFlow = (id: number) => invoke<Paged<Track>>('deezer_user_flow', { id });
export const playlistTracks = (id: number) =>
  invoke<Paged<Track>>('deezer_playlist_tracks', { id });
export const chartTracks = () => invoke<Paged<Track>>('deezer_chart_tracks');
export const userAlbums = (id: number) => invoke<Paged<Album>>('deezer_user_albums', { id });
export const userArtists = (id: number) => invoke<Paged<Artist>>('deezer_user_artists', { id });
export const albumTracks = (id: number) => invoke<Paged<Track>>('deezer_album_tracks', { id });
export const artistTop = (id: number) => invoke<Paged<Track>>('deezer_artist_top', { id });
export const artistInfo = (id: number) => invoke<Artist>('deezer_artist', { id });
export const artistAlbums = (id: number) => invoke<Paged<Album>>('deezer_artist_albums', { id });

export const resolveTrack = (
  trackId: number,
  artist: string,
  title: string,
  duration: number,
  force = false,
) =>
  invoke<Resolved>('resolve_track', {
    trackId,
    artist,
    title,
    duration,
    force,
  });

export const resolverInvalidate = (trackId: number) =>
  invoke<void>('resolver_invalidate', { trackId });

export function parseUserId(input: string): number | null {
  const trimmed = input.trim();
  if (/^\d+$/.test(trimmed)) return Number(trimmed);
  const m = trimmed.match(/deezer\.com\/(?:[a-z]{2}\/)?profile\/(\d+)/i);
  return m ? Number(m[1]) : null;
}
