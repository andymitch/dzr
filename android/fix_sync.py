import re

with open("app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/SettingsCategoryScreen.kt", "r") as f:
    settings_lines = f.readlines()

new_settings_lines = []
skip = False
brace_count = 0

for line in settings_lines:
    if "RefreshLibraryItem(" in line:
        skip = True
        brace_count = 1
        if "{" in line:
            brace_count += line.count("{") - line.count("}")
        continue
    
    if skip:
        if "{" in line:
            brace_count += line.count("{")
        if "}" in line:
            brace_count -= line.count("}")
        
        if "(" in line:
            brace_count += line.count("(")
        if ")" in line:
            brace_count -= line.count(")")
            
        if brace_count <= 0:
            skip = False
        continue
        
    new_settings_lines.append(line)

with open("app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/SettingsCategoryScreen.kt", "w") as f:
    f.writelines(new_settings_lines)


with open("app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/SettingsComponents.kt", "r") as f:
    components_lines = f.readlines()

new_components_lines = []
skip = False
for line in components_lines:
    if "import com.lostf1sh.pixelplayeross.data.worker.SyncProgress" in line:
        continue
    if "fun RefreshLibraryItem(" in line or "fun syncPhaseLabel(" in line:
        skip = True
    if skip and line.startswith("        )"):
        # End of syncPhaseLabel
        skip = False
        continue
    if skip and line == "}\n":
        skip = False
        continue
    if not skip:
        new_components_lines.append(line)

with open("app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/SettingsComponents.kt", "w") as f:
    f.writelines(new_components_lines)
