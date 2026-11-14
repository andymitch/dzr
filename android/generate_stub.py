import re

with open("app/src/main/java/com/lostf1sh/pixelplayeross/data/repository/MusicRepository.kt") as f:
    content = f.read()

# Extract all fun ... inside the interface
methods = []
for match in re.finditer(r'(suspend\s+)?fun\s+(\w+)\s*\(([^)]*)\)\s*(:\s*([^/\{]+))?', content):
    suspend = match.group(1) or ""
    name = match.group(2)
    args_raw = match.group(3)
    ret_type_full = match.group(5)
    
    # Ignore comments in args or return type
    ret_type = ret_type_full.strip() if ret_type_full else "Unit"
    ret_type = re.sub(r'//.*', '', ret_type).strip()
    
    args = []
    if args_raw.strip():
        for arg in args_raw.split(','):
            arg = arg.strip()
            # remove default values
            arg = re.sub(r'\s*=.*', '', arg).strip()
            args.append(arg)
            
    args_str = ", ".join(args)
    
    if ret_type == "Unit":
        body = "{}"
    elif ret_type.startswith("Flow<"):
        body = "= kotlinx.coroutines.flow.flowOf()"
    elif ret_type.startswith("List<"):
        body = "= emptyList()"
    elif ret_type.startswith("Set<"):
        body = "= emptySet()"
    elif ret_type.startswith("Result<"):
        body = "= Result.success(TODO())"
    elif ret_type == "Boolean":
        body = "= false"
    elif ret_type == "Int":
        body = "= 0"
    elif ret_type == "Long":
        body = "= 0L"
    elif ret_type.endswith("?"):
        body = "= null"
    else:
        body = "= TODO()"

    methods.append(f"    override {suspend}fun {name}({args_str}): {ret_type} {body}")

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
