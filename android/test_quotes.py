with open("app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/LibraryScreen.kt", "r") as f:
    lines = f.readlines()
in_string = False
for i, line in enumerate(lines):
    clean = line.split("//")[0]
    escaped = False
    for c in clean:
        if c == "\\" and not escaped:
            escaped = True
            continue
        if c == '"' and not escaped:
            in_string = not in_string
        escaped = False
    if in_string:
        print(f"Unclosed string at line {i+1}: {line.strip()}")
        in_string = False # reset for next line
