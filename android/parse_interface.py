import re

with open("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepository.kt", 'r') as f:
    content = f.read()

# Remove block comments
content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)

lines = content.split('\n')
out = [
    "package com.lostf1sh.pixelplayeross.data.repository",
    "import android.net.Uri",
    "import javax.inject.Inject",
    "import javax.inject.Singleton",
    "import androidx.paging.PagingData",
    "import com.lostf1sh.pixelplayeross.data.model.*",
    "import kotlinx.coroutines.flow.Flow",
    "import kotlinx.coroutines.flow.emptyFlow",
    "import kotlinx.coroutines.flow.flowOf",
    "@Singleton",
    "class MusicRepositoryImpl @Inject constructor(",
    "    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context",
    ") : MusicRepository {"
]

current_func = ""
for line in lines:
    # remove inline comments
    line = re.sub(r'//.*', '', line).strip()
    if not line or line.startswith("@") or line.startswith("import ") or line.startswith("package ") or line.startswith("interface "):
        continue
    if line == "}":
        continue
        
    current_func += " " + line
    
    # A function declaration is complete if it has matching parentheses
    if current_func.count("(") == current_func.count(")") and current_func.count("(") > 0:
        f = current_func.strip()
        current_func = ""
        
        # Remove default arguments (e.g. limit: Int = 50 -> limit: Int)
        f = re.sub(r'=\s*[A-Za-z0-9_.]+', '', f)
        
        # Determine return type
        m = re.search(r':\s*([A-Za-z0-9_<>?,\. ]+)$', f)
        ret_type = m.group(1).strip() if m else ""
        
        ret = ""
        if not ret_type:
            ret = "Unit"
        else:
            sig_lower = ret_type.lower()
            if "flow<pagingdata" in sig_lower:
                ret = "emptyFlow()"
            elif "flow<list" in sig_lower:
                ret = "flowOf(emptyList())"
            elif "flow<set" in sig_lower:
                ret = "flowOf(emptySet())"
            elif "flow<int" in sig_lower:
                ret = "flowOf(0)"
            elif "flow<" in sig_lower:
                ret = "flowOf(null)"
            elif "list<" in sig_lower:
                ret = "emptyList()"
            elif "set<" in sig_lower:
                ret = "emptySet()"
            elif "result<" in sig_lower:
                ret = 'Result.failure(Exception("Not implemented"))'
            elif "boolean" in sig_lower:
                ret = "false"
            elif "?" in ret_type:
                ret = "null"
            elif "long" in sig_lower:
                ret = "0L"
            elif "int" in sig_lower:
                ret = "0"
            else:
                ret = "null /* Fallback */"
                
        if ret == "Unit":
            out.append(f"    override {f} {{ }}")
        else:
            out.append(f"    override {f} = {ret}")

out.append("}")

with open("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepositoryImpl.kt", 'w') as f:
    f.write("\n".join(out))

