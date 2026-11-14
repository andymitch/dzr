import re

with open("app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/SettingsCategoryScreen.kt", "r") as f:
    lines = f.readlines()

new_lines = []
skip = False
brace_count = 0

for line in lines:
    if line.strip() == "SettingsCategory.BACKUP_RESTORE -> {":
        skip = True
        brace_count = 1
        continue
    
    if skip:
        if "{" in line:
            brace_count += line.count("{")
        if "}" in line:
            brace_count -= line.count("}")
        if brace_count == 0:
            skip = False
        continue

    # Also skip any top-level functions or composables with Backup in name
    if re.match(r'^@?(OptIn|Composable|private|fun).*Backup.*', line):
        if not re.search(r'BackupHistoryEntry|BackupOperationType|BackupSection|BackupTransferProgressUpdate', line):
            pass # we'll just let python skip the next lines if we do a block skip, wait better just rely on removing the functions manually below.
            
    new_lines.append(line)

with open("app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/SettingsCategoryScreen.kt", "w") as f:
    f.writelines(new_lines)

