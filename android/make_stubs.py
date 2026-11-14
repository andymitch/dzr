import re
import sys

def parse_interface(filepath, classname, interfacename):
    with open(filepath, 'r') as f:
        content = f.read()
    
    # Strip comments
    content = re.sub(r'//.*', '', content)
    content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)
    
    lines = content.split('\n')
    funcs = []
    
    current_func = ""
    for line in lines:
        line = line.strip()
        if not line or line.startswith("@") or line.startswith("import ") or line.startswith("package ") or line.startswith("interface "):
            continue
        if line == "}":
            continue
            
        current_func += " " + line
        if ")" in current_func:
            # Check if we have reached the end of the return type if any
            # It usually ends the line.
            funcs.append(current_func.strip())
            current_func = ""

    out = [f"package com.lostf1sh.pixelplayeross.data.repository",
           f"import android.net.Uri",
           f"import javax.inject.Inject",
           f"import javax.inject.Singleton",
           f"import androidx.paging.PagingData",
           f"import com.lostf1sh.pixelplayeross.data.model.*",
           f"import kotlinx.coroutines.flow.Flow",
           f"import kotlinx.coroutines.flow.emptyFlow",
           f"import kotlinx.coroutines.flow.flowOf",
           f"@Singleton",
           f"class {classname} @Inject constructor(",
           f"    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context",
           f") : {interfacename} {{"]
           
    for f in funcs:
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
                ret = 'Result.success(Pair("", emptyList()))' if "pair" in sig_lower else 'Result.failure(Exception("Not implemented"))'
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
    
    with open(filepath.replace(".kt", "Impl.kt"), 'w') as f:
        f.write("\n".join(out))

parse_interface("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepository.kt", "MusicRepositoryImpl", "MusicRepository")
parse_interface("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/LyricsRepository.kt", "LyricsRepositoryImpl", "LyricsRepository")
parse_interface("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/TransitionRepository.kt", "TransitionRepositoryImpl", "TransitionRepository")

