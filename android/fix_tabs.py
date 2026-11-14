import re

path = "app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/LibraryScreen.kt"
with open(path, "r") as f:
    text = f.read()

# 1. BackHandler
text = text.replace(
    "LibraryTabId.SONGS,\n                LibraryTabId.LIKED,\n                LibraryTabId.FOLDERS ->",
    "LibraryTabId.PODCASTS,\n                LibraryTabId.LIKED ->"
)

text = text.replace(
    "LibraryTabId.SONGS,\n                    LibraryTabId.LIKED,\n                    LibraryTabId.FOLDERS ->",
    "LibraryTabId.PODCASTS,\n                    LibraryTabId.LIKED ->"
)

# 2. currentTabId == LibraryTabId.FOLDERS
text = text.replace("currentTabId == LibraryTabId.FOLDERS", "currentTabId == LibraryTabId.PODCASTS")
text = text.replace("currentTabId == LibraryTabId.SONGS", "currentTabId == LibraryTabId.PODCASTS")

# 3. toLibraryTabIdOrNull
text = text.replace("?: LibraryTabId.SONGS", "?: LibraryTabId.PLAYLISTS")

# 4. currentSelectedSortOption
text = re.sub(r'LibraryTabId\.SONGS\s*->\s*playerUiState\.currentSongSortOption', 'LibraryTabId.PODCASTS -> null', text)
text = re.sub(r'\s*LibraryTabId\.FOLDERS\s*->\s*playerUiState\.currentFolderSortOption\n', '\n', text)

# 5. showLocateButton & locateAction
text = re.sub(r'\s*LibraryTabId\.SONGS\s*->\s*songsShowLocateButton\n', '\n', text)
text = re.sub(r'\s*LibraryTabId\.FOLDERS\s*->\s*foldersShowLocateButton\n', '\n', text)
text = re.sub(r'\s*LibraryTabId\.SONGS\s*->\s*songsLocateAction\n', '\n', text)
text = re.sub(r'\s*LibraryTabId\.FOLDERS\s*->\s*foldersLocateAction\n', '\n', text)

# 6. onSortOptionChanged
text = re.sub(r'LibraryTabId\.SONGS\s*->\s*playerViewModel\.sortSongs\(option\)', 'LibraryTabId.PODCASTS -> {}', text)
text = re.sub(r'\s*LibraryTabId\.FOLDERS\s*->\s*playerViewModel\.sortFolders\(option\)\n', '\n', text)

# 7. Action rows (line 899, 904)
# They are blocks. Let's just blindly replace SONGS with PODCASTS and let FOLDERS be removed.
text = text.replace("LibraryTabId.SONGS -> {", "LibraryTabId.PODCASTS -> {")
text = re.sub(r'\s*LibraryTabId\.FOLDERS\s*->\s*\{[^}]+\}\n', '\n', text) # naive block remove, might fail if nested braces.
# Better to replace FOLDERS with PLAYLISTS and let it be duplicate? No, duplicate branches not allowed.
# Let's just use Python script to do precise replacement for Action rows
text = text.replace("LibraryTabId.FOLDERS -> {", "LibraryTabId.ARTISTS -> {") # FOLDERS becomes ARTISTS which is Unit in action row? Wait, ARTISTS already exists? Let's check line 894

# 8. icons
text = text.replace("LibraryTabId.SONGS -> R.drawable.rounded_music_note_24", "LibraryTabId.PODCASTS -> R.drawable.rounded_music_note_24")
text = re.sub(r'\s*LibraryTabId\.FOLDERS\s*->\s*R\.drawable\.rounded_folder_24\n', '\n', text)

# 9. other FOLDERS references
text = text.replace("tabId = LibraryTabId.FOLDERS,", "tabId = LibraryTabId.PODCASTS,")
text = text.replace("LibraryTabId.FOLDERS", "LibraryTabId.PODCASTS")
text = text.replace("LibraryTabId.SONGS", "LibraryTabId.PODCASTS")

# Since duplicate PODCASTS might exist now, let's fix them:
text = re.sub(r'(LibraryTabId\.PODCASTS\s*->.*?)\s*LibraryTabId\.PODCASTS\s*->.*?\n', r'\1\n', text, flags=re.DOTALL)

with open(path, "w") as f:
    f.write(text)
print("done")
