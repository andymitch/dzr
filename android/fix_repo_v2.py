import re

with open("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepository.kt") as f:
    content = f.read()

# Remove multi-line comments
content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)

# Remove single line comments
content = re.sub(r'//.*', '', content)

# Find all function signatures
# A function signature might span multiple lines. We can just search for "fun " up to ")" and the optional return type.
matches = re.finditer(r'(suspend\s+)?fun\s+([a-zA-Z0-9_]+)\s*\((.*?)\)\s*(:\s*([a-zA-Z0-9_<>,\?\.\s]+))?', content, flags=re.DOTALL)

methods = []
for m in matches:
    is_suspend = m.group(1) or ""
    name = m.group(2).strip()
    
    # Clean up arguments (remove newlines, collapse spaces, remove default values)
    args_raw = m.group(3)
    args_raw = args_raw.replace('\n', ' ')
    
    clean_args = []
    if args_raw.strip():
        for arg in args_raw.split(','):
            arg = arg.strip()
            # remove default value assignment like `= com.lost...`
            arg = re.sub(r'\s*=.*', '', arg).strip()
            clean_args.append(arg)
            
    args_str = ", ".join(clean_args)
    
    ret_type = m.group(5)
    if ret_type:
        ret_type = ret_type.replace('\n', ' ').strip()
        ret_type_str = f": {ret_type}"
    else:
        ret_type_str = ""
        
    methods.append(f"    override {is_suspend}fun {name}({args_str}){ret_type_str} = TODO()")

out = """package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import androidx.paging.PagingData
import com.lostf1sh.pixelplayeross.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton
import android.net.Uri

@Singleton
class MusicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : MusicRepository {

""" + "\n".join(methods) + "\n}\n"

with open("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepositoryImpl.kt", "w") as f:
    f.write(out)
