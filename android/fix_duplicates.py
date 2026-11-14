path = "app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/LibraryScreen.kt"
with open(path, "r") as f:
    lines = f.readlines()

new_lines = []
skip = 0

for i, line in enumerate(lines):
    if skip > 0:
        skip -= 1
        continue
        
    if "LibraryTabId.PODCASTS_DUPLICATE -> {" in line:
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
        
    new_lines.append(line)

with open(path, "w") as f:
    f.writelines(new_lines)
