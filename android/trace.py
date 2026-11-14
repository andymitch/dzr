level = 0
with open("app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/LibraryScreen.kt", "r") as f:
    for line in f:
        level += line.count("{") - line.count("}")
print("Final level:", level)
