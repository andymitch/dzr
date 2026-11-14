#!/bin/bash
repo_file="app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepository.kt"
impl_file="app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepositoryImpl.kt"

# Read the interface, remove comments, replace "interface MusicRepository {" with class + override
cat "$repo_file" | \
sed -e 's/interface MusicRepository {/@Singleton\nclass MusicRepositoryImpl @Inject constructor(\n    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context\n) : MusicRepository {/' | \
awk '
BEGIN {
    in_comment = 0
}
{
    if ($0 ~ /\/\*/) in_comment = 1
    
    if (in_comment == 0) {
        # remove single line comments
        gsub(/\/\/.*$/, "")
        
        # if the line starts with fun or suspend fun, add override
        if ($0 ~ /^[[:space:]]*(suspend )?fun /) {
            sub(/^[[:space:]]*/, "    override ")
        }
        
        print $0
    }
    
    if ($0 ~ /\*\//) in_comment = 0
}
' > temp.kt

# Now we need to append = TODO() to function declarations.
# Since signatures can span multiple lines, we can just replace the trailing > or ) or ? with the same character followed by = TODO() IF it is a function declaration.
# Let's just use Python for the parsing because it's easier to balance parentheses.

python3 -c '
import sys
text = open("temp.kt").read()

out = []
lines = text.split("\n")
i = 0
while i < len(lines):
    line = lines[i]
    if "override " in line:
        # collect lines until we have balanced parentheses
        sig = line
        open_parens = sig.count("(")
        close_parens = sig.count(")")
        while open_parens > close_parens and i + 1 < len(lines):
            i += 1
            sig += " " + lines[i].strip()
            open_parens = sig.count("(")
            close_parens = sig.count(")")
            
        # now sig has the balanced parentheses, wait for return type
        # return type might be on the next line if it starts with :
        while i + 1 < len(lines) and (":" in lines[i+1] or ">" in lines[i+1] or "?" in lines[i+1] or lines[i+1].strip().startswith(")")):
            if "override " in lines[i+1]: break
            if "fun " in lines[i+1]: break
            i += 1
            sig += " " + lines[i].strip()
            
        out.append(sig + " = TODO()")
    else:
        out.append(line)
    i += 1

open("'$impl_file'", "w").write("\n".join(out))
'

rm temp.kt
