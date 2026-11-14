import re

with open("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepository.kt") as f:
    text = f.read()

# remove comments
text = re.sub(r'/\*.*?\*/', '', text, flags=re.DOTALL)
text = re.sub(r'//.*', '', text)

# find all 'fun ...' declarations
matches = re.findall(r'(suspend\s+)?fun\s+([a-zA-Z0-9_]+)\s*\((.*?)\)\s*(:\s*[a-zA-Z0-9_<>,?\s]+)?', text, flags=re.DOTALL)

lines = []
for m in matches:
    suspend = m[0] or ""
    name = m[1]
    args = m[2].strip()
    # simplify args (remove default values = ...)
    clean_args = []
    if args:
        for arg in args.split(','):
            clean_arg = re.sub(r'\s*=.*', '', arg).strip()
            clean_args.append(clean_arg)
    args_str = ", ".join(clean_args)
    
    ret_type = m[3].strip() if m[3] else ""
    # remove newlines from ret_type
    ret_type = ret_type.replace('\n', ' ').replace('\r', '')
    
    lines.append(f"    override {suspend}fun {name}({args_str}){ret_type} = TODO()")

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

""" + "\n".join(lines) + "\n}\n"

with open("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepositoryImpl.kt", "w") as f:
    f.write(out)
