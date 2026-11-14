level = 0
with open("app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/LibraryScreen.kt", "r") as f:
    for i, line in enumerate(f):
        clean = line.split("//")[0]
        c_open = clean.count("{")
        c_close = clean.count("}")
        level += c_open - c_close
print(f"Final level: {level}")
