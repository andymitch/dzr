import subprocess
orig = subprocess.check_output(["git", "show", "HEAD:app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/LibraryScreen.kt"]).decode('utf-8')
stack = []
for i, line in enumerate(orig.splitlines()):
    clean = line.split("//")[0]
    for c in clean:
        if c == "{": stack.append(i+1)
        elif c == "}":
            if stack: stack.pop()
    if "//Grad box" in line:
        print(f"Original stack before Grad box: {stack}")
        break
