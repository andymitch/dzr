import re

path = "app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/LibraryScreen.kt"
with open(path, "r") as f:
    content = f.read()

# 478-480
content = content.replace("""                LibraryTabId.SONGS,
                LibraryTabId.LIKED,
                LibraryTabId.FOLDERS -> isSelectionMode""", """                LibraryTabId.PODCASTS,
                LibraryTabId.LIKED -> isSelectionMode""")

# 509-511
content = content.replace("""                    LibraryTabId.SONGS,
                    LibraryTabId.LIKED,
                    LibraryTabId.FOLDERS -> {""", """                    LibraryTabId.PODCASTS,
                    LibraryTabId.LIKED -> {""")

# 675
content = content.replace("val tabId = rawId.toLibraryTabIdOrNull() ?: LibraryTabId.SONGS", "val tabId = rawId.toLibraryTabIdOrNull() ?: LibraryTabId.PLAYLISTS")

# 804-811
content = content.replace("""                            LibraryTabId.SONGS -> playerUiState.currentSongSortOption
                            LibraryTabId.ALBUMS -> playerUiState.currentAlbumSortOption
                            LibraryTabId.ARTISTS -> playerUiState.currentArtistSortOption
                            LibraryTabId.PLAYLISTS -> playlistUiState.currentPlaylistSortOption
                            LibraryTabId.LIKED -> playerUiState.currentFavoriteSortOption
                            LibraryTabId.FOLDERS -> playerUiState.currentFolderSortOption""", """                            LibraryTabId.PODCASTS -> null
                            LibraryTabId.ALBUMS -> playerUiState.currentAlbumSortOption
                            LibraryTabId.ARTISTS -> playerUiState.currentArtistSortOption
                            LibraryTabId.PLAYLISTS -> playlistUiState.currentPlaylistSortOption
                            LibraryTabId.LIKED -> playerUiState.currentFavoriteSortOption""")

# 814-816
content = content.replace("""                            LibraryTabId.SONGS -> songsShowLocateButton
                            LibraryTabId.LIKED -> likedShowLocateButton
                            LibraryTabId.FOLDERS -> foldersShowLocateButton""", """                            LibraryTabId.PODCASTS -> false
                            LibraryTabId.LIKED -> likedShowLocateButton""")

# 820-822
content = content.replace("""                            LibraryTabId.SONGS -> songsLocateAction
                            LibraryTabId.LIKED -> likedLocateAction
                            LibraryTabId.FOLDERS -> foldersLocateAction""", """                            LibraryTabId.PODCASTS -> null
                            LibraryTabId.LIKED -> likedLocateAction""")

# 829-834
content = content.replace("""                                    LibraryTabId.SONGS -> playerViewModel.sortSongs(option)
                                    LibraryTabId.ALBUMS -> playerViewModel.sortAlbums(option)
                                    LibraryTabId.ARTISTS -> playerViewModel.sortArtists(option)
                                    LibraryTabId.PLAYLISTS -> playlistViewModel.sortPlaylists(option)
                                    LibraryTabId.LIKED -> playerViewModel.sortFavoriteSongs(option)
                                    LibraryTabId.FOLDERS -> playerViewModel.sortFolders(option)""", """                                    LibraryTabId.PODCASTS -> {}
                                    LibraryTabId.ALBUMS -> playerViewModel.sortAlbums(option)
                                    LibraryTabId.ARTISTS -> playerViewModel.sortArtists(option)
                                    LibraryTabId.PLAYLISTS -> playlistViewModel.sortPlaylists(option)
                                    LibraryTabId.LIKED -> playerViewModel.sortFavoriteSongs(option)""")

# 948
content = content.replace("showStorageFilterButton = currentTabId == LibraryTabId.SONGS ||", "showStorageFilterButton = currentTabId == LibraryTabId.PODCASTS ||")

# 1122 and 1242
content = content.replace("LibraryTabId.SONGS -> {", "LibraryTabId.PODCASTS -> {")
content = content.replace("LibraryTabId.FOLDERS -> {", "LibraryTabId.PODCASTS_DUPLICATE -> {")

# 2199-2203
content = content.replace("""    LibraryTabId.SONGS -> R.drawable.rounded_music_note_24
    LibraryTabId.ALBUMS -> R.drawable.rounded_album_24
    LibraryTabId.ARTISTS -> R.drawable.rounded_artist_24
    LibraryTabId.PLAYLISTS -> R.drawable.rounded_playlist_play_24
    LibraryTabId.FOLDERS -> R.drawable.rounded_folder_24""", """    LibraryTabId.PODCASTS -> R.drawable.rounded_music_note_24
    LibraryTabId.ALBUMS -> R.drawable.rounded_album_24
    LibraryTabId.ARTISTS -> R.drawable.rounded_artist_24
    LibraryTabId.PLAYLISTS -> R.drawable.rounded_playlist_play_24""")

# others
content = content.replace("currentTabId == LibraryTabId.FOLDERS", "false")
content = content.replace("LibraryTabId.FOLDERS", "LibraryTabId.PODCASTS")

with open(path, "w") as f:
    f.write(content)
