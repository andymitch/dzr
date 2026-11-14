with open("app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/LibraryScreen.kt", "r") as f:
    lines = f.readlines()
level = 0
for i, line in enumerate(lines):
    clean = line.split("//")[0]
    level += clean.count("(") - clean.count(")")
    if level < 0:
        print(f"Parenthesis mismatch at line {i+1}: {line.strip()}")
        break
