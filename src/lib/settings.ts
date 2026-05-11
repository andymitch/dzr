import { LazyStore } from '@tauri-apps/plugin-store';
import { writable } from 'svelte/store';

const store = new LazyStore('settings.json');

export const userId = writable<number | null>(null);

let loaded = false;
export async function loadSettings() {
  if (loaded) return;
  loaded = true;
  const v = await store.get<number>('deezer_user_id');
  if (typeof v === 'number') userId.set(v);
}

export async function setUserId(id: number | null) {
  if (id == null) {
    await store.delete('deezer_user_id');
  } else {
    await store.set('deezer_user_id', id);
  }
  await store.save();
  userId.set(id);
}
