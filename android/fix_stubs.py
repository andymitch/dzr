import re
import sys

def replace_todo(match):
    signature = match.group(1) # e.g. override fun getAudioFiles()
    return_type = match.group(2) # e.g. : Flow<List<Song>>
    
    ret = ""
    if not return_type:
        return_type = ""
        ret = "Unit"
    else:
        sig_lower = return_type.lower()
        if "flow<pagingdata" in sig_lower:
            ret = "kotlinx.coroutines.flow.emptyFlow()"
        elif "flow<list" in sig_lower:
            ret = "kotlinx.coroutines.flow.flowOf(emptyList())"
        elif "flow<set" in sig_lower:
            ret = "kotlinx.coroutines.flow.flowOf(emptySet())"
        elif "flow<int" in sig_lower:
            ret = "kotlinx.coroutines.flow.flowOf(0)"
        elif "flow<" in sig_lower:
            ret = "kotlinx.coroutines.flow.flowOf(null)"
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
        return signature + return_type + " { }"
    return signature + return_type + " = " + ret

for filename in sys.argv[1:]:
    with open(filename, 'r') as f:
        content = f.read()
    
    # Matches: override fun name(...) : ReturnType = TODO()
    content = re.sub(r'(override\s+(?:suspend\s+)?fun\s+[^{=]+\))(\s*:\s*[A-Za-z0-9_<>?,\.\s]+)?\s*=\s*TODO\(\)', 
                     lambda m: replace_todo(m), content)
    
    with open(filename, 'w') as f:
        f.write(content)
