import sys

path = "app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/LibraryScreen.kt"
with open(path, "r") as f:
    lines = f.readlines()

new_lines = []
skip = 0

for i, line in enumerate(lines):
    if skip > 0:
        skip -= 1
        continue
    
    # Backhandler selections
    if "LibraryTabId.SONGS," in line and "LibraryTabId.LIKED," in lines[i+1] and "LibraryTabId.FOLDERS ->" in lines[i+2]:
        new_lines.append("                LibraryTabId.PODCASTS,\n")
        new_lines.append("                LibraryTabId.LIKED -> isSelectionMode\n")
        skip = 2
        continue
        
    if "LibraryTabId.SONGS," in line and "LibraryTabId.LIKED," in lines[i+1] and "LibraryTabId.FOLDERS -> {" in lines[i+2]:
        new_lines.append("                    LibraryTabId.PODCASTS,\n")
        new_lines.append("                    LibraryTabId.LIKED -> {\n")
        skip = 2
        continue

    # showLocateButton
    if "LibraryTabId.SONGS -> songsShowLocateButton" in line:
        continue
    if "LibraryTabId.FOLDERS -> foldersShowLocateButton" in line:
        continue
        
    # locateAction
    if "LibraryTabId.SONGS -> songsLocateAction" in line:
        continue
    if "LibraryTabId.FOLDERS -> foldersLocateAction" in line:
        continue
        
    # currentSelectedSortOption
    if "LibraryTabId.SONGS -> playerUiState.currentSongSortOption" in line:
        new_lines.append(line.replace("LibraryTabId.SONGS -> playerUiState.currentSongSortOption", "LibraryTabId.PODCASTS -> null"))
        continue
    if "LibraryTabId.FOLDERS -> playerUiState.currentFolderSortOption" in line:
        continue
        
    # onSortOptionChanged
    if "LibraryTabId.SONGS -> playerViewModel.sortSongs(option)" in line:
        new_lines.append(line.replace("LibraryTabId.SONGS -> playerViewModel.sortSongs(option)", "LibraryTabId.PODCASTS -> {}"))
        continue
    if "LibraryTabId.FOLDERS -> playerViewModel.sortFolders(option)" in line:
        continue

    # Icons
    if "LibraryTabId.SONGS -> R.drawable.rounded_music_note_24" in line:
        new_lines.append(line.replace("SONGS", "PODCASTS"))
        continue
    if "LibraryTabId.FOLDERS -> R.drawable.rounded_folder_24" in line:
        continue

    # tabId fallback
    if "?: LibraryTabId.SONGS" in line:
        new_lines.append(line.replace("?: LibraryTabId.SONGS", "?: LibraryTabId.PLAYLISTS"))
        continue

    # currentTabId == FOLDERS / SONGS
    if "currentTabId == LibraryTabId.FOLDERS" in line:
        new_lines.append(line.replace("currentTabId == LibraryTabId.FOLDERS", "false"))
        continue
    if "currentTabId == LibraryTabId.SONGS" in line:
        new_lines.append(line.replace("currentTabId == LibraryTabId.SONGS", "false"))
        continue

    # Action Rows
    if "LibraryTabId.SONGS -> {" in line:
        new_lines.append(line.replace("LibraryTabId.SONGS", "LibraryTabId.PODCASTS"))
        continue
        
    if "LibraryTabId.FOLDERS -> {" in line:
        # We need to skip this block. Count braces
        braces = 1
        j = i + 1
        while j < len(lines):
            braces += lines[j].count('{')
            braces -= lines[j].count('}')
            if braces == 0:
                break
            j += 1
        skip = j - i
        continue

    # If just a random FOLDERS/SONGS replacement
    if "LibraryTabId.FOLDERS" in line:
        new_lines.append(line.replace("LibraryTabId.FOLDERS", "LibraryTabId.PODCASTS"))
        continue
        
    if "LibraryTabId.SONGS" in line:
        new_lines.append(line.replace("LibraryTabId.SONGS", "LibraryTabId.PODCASTS"))
        continue

    new_lines.append(line)

with open(path, "w") as f:
    f.writelines(new_lines)

print("Replacement done")
