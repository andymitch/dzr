import re
with open("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepositoryImpl.kt") as f:
    text = f.read()

# Add the missing imports
text = text.replace("import android.net.Uri\n", "import android.net.Uri\nimport javax.inject.Inject\nimport javax.inject.Singleton\n")

# Strip default values: = followed by something up to , or )
# Since default values don't contain commas or parens inside them (e.g. StorageFilter.ALL, 1, false)
# We can just use a simple regex for arguments
text = re.sub(r'(\w+\s*:\s*[A-Za-z0-9_<>.]+)\s*=\s*[^,)]+', r'\1', text)

with open("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepositoryImpl.kt", "w") as f:
    f.write(text)
