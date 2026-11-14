with open("app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/LibraryScreen.kt", "r") as f:
    lines = f.readlines()
stack = []
for i, line in enumerate(lines):
    clean = line.split("//")[0]
    for c in clean:
        if c == "{": stack.append(i+1)
        elif c == "}":
            start = stack.pop() if stack else -1
            if i+1 == 1768:
                print(f"Line 1768 closes {start}")
