import re
import sys

def create_stub(interface_path, impl_path, class_name, interface_name):
    with open(interface_path, 'r') as f:
        content = f.read()

    # Extract all functions
    methods = re.findall(r'((?:suspend\s+)?fun\s+[^{]+)', content)

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
           f"class {class_name} @Inject constructor(",
           f"    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context",
           f") : {interface_name} {{"]

    for m in methods:
        m = m.strip()
        if m.startswith("/*") or m.startswith("*"): continue
        # Handle default arguments: remove them
        m = re.sub(r'=\s*[^,)]+', '', m)
        
        return_type_match = re.search(r':\s*([A-Za-z0-9_<>?,\.\s]+)$', m)
        return_type = return_type_match.group(1).strip() if return_type_match else ""
        
        ret = ""
        if not return_type:
            ret = "Unit"
        else:
            sig_lower = return_type.lower()
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
            elif "?" in return_type:
                ret = "null"
            elif "long" in sig_lower:
                ret = "0L"
            elif "int" in sig_lower:
                ret = "0"
            else:
                ret = "null /* Fallback */"
                
        if ret == "Unit":
            out.append(f"    override {m} {{ }}")
        else:
            out.append(f"    override {m} = {ret}")

    out.append("}")
    
    with open(impl_path, 'w') as f:
        f.write("\n".join(out))

create_stub("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepository.kt", 
            "app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepositoryImpl.kt",
            "MusicRepositoryImpl", "MusicRepository")

create_stub("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/LyricsRepository.kt", 
            "app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/LyricsRepositoryImpl.kt",
            "LyricsRepositoryImpl", "LyricsRepository")
            
create_stub("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/TransitionRepository.kt", 
            "app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/TransitionRepositoryImpl.kt",
            "TransitionRepositoryImpl", "TransitionRepository")
