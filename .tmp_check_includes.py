#!/usr/bin/env python3
"""Scan all #include in melonDS-related sources and report missing headers."""
import os, re, sys

ROOT = "/data/data/com.termux/files/home/my-project"
MELON_DIR = os.path.join(ROOT, "core/melonds_temp/melonDS-android-lib-master/src")
CORE_DIR = os.path.join(ROOT, "core/melonds/libretro")
LIBRETRO_COMM = os.path.join(CORE_DIR, "libretro-common")

# include search paths mirroring CMakeLists.txt
INCLUDE_DIRS = [
    os.path.join(LIBRETRO_COMM, "include"),
    CORE_DIR,
    MELON_DIR,
    os.path.join(MELON_DIR, "teakra/include"),
    os.path.join(MELON_DIR, "teakra/src"),
    os.path.join(MELON_DIR, "frontend"),
    os.path.join(MELON_DIR, "ARMJIT_A64"),
    os.path.join(MELON_DIR, "dolphin"),
]

# also treat the src subdirs themselves (relative include of own dir handled by compiler)
# plus libretro-common subdirs
LIBRETRO_COMM_SUBDIRS = []
for d in os.listdir(LIBRETRO_COMM):
    p = os.path.join(LIBRETRO_COMM, d)
    if os.path.isdir(p):
        LIBRETRO_COMM_SUBDIRS.append(p)

ANGLE_INC = [os.path.join(MELON_DIR, "teakra/include"),
             os.path.join(MELON_DIR, "teakra/src")]

# NDK-ish headers that clang provides; treat common ones as "system" (ok)
SYSTEM_HEADERS = {
    "string.h", "stdlib.h", "stdio.h", "stdint.h", "stdarg.h", "stddef.h",
    "stdbool.h", "math.h", "time.h", "limits.h", "float.h", "ctype.h",
    "assert.h", "errno.h", "fcntl.h", "unistd.h", "pthread.h", "signal.h",
    "inttypes.h", "stdalign.h", "malloc.h", "wchar.h", "strings.h",
    "sys/types.h", "sys/stat.h", "sys/time.h", "sys/mman.h", "sys/utsname.h",
    "sys/socket.h", "sys/ioctl.h", "sys/param.h", "sys/un.h", "sys/select.h",
    "sys/wait.h", "sys/syscall.h", "netinet/in.h", "arpa/inet.h",
    "endian.h", "byteswap.h", "jni.h", "android/log.h", "EGL/egl.h",
    "EGL/eglext.h", "GLES2/gl2.h", "GLES2/gl2ext.h", "GLES2/gl2platform.h",
    "GLES3/gl3.h", "GL/GL.h",
    "new", "typeinfo", "cstring", "cstdio", "cstdlib", "cstdint", "cstddef",
    "cmath", "cassert", "cctype", "climits", "cfloat", "cstdarg", "ctime",
    "algorithm", "array", "atomic", "bitset", "chrono", "condition_variable",
    "deque", "exception", "forward_list", "fstream", "functional", "future",
    "initializer_list", "iomanip", "ios", "iosfwd", "iostream", "istream",
    "iterator", "limits", "list", "locale", "map", "memory", "memory_resource",
    "mutex", "numeric", "optional", "ostream", "queue", "random", "ratio",
    "regex", "set", "shared_mutex", "sstream", "stack", "stdexcept",
    "streambuf", "string", "string_view", "strstream", "system_error",
    "thread", "tuple", "type_traits", "typeindex", "unordered_map",
    "unordered_set", "utility", "valarray", "vector", "variant", "version",
    "cinttypes", "csignal", "cstdio", "cstdlib", "cstring", "cwchar", "cwctype",
    "stdatomic.h", "stdnoreturn.h", "uchar.h", "complex.h", "tgmath.h",
    "sys/eventfd.h", "sys/epoll.h", "poll.h",
    "dolphin/CommonFuncs.h",
    "libretro.h", "libretro_core_options.h", "libretro_core_options_intl.h",
}

# map of header -> files that include it (relative include e.g. "teakra/../....")
inc_re = re.compile(r'#\s*include\s*[<"]\s*([^>"]+?)\s*[>"]')

missing_quote = {}  # name -> list of including files (for quote includes not found in any include dir)
missing_angle = {}
checked = set()

src_exts = (".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".hxx", ".S", ".s", ".asm")

def resolve_rel(base, rel):
    return os.path.normpath(os.path.join(base, rel))

def find_header(name):
    global checked
    if name in SYSTEM_HEADERS:
        return "system"
    # search include dirs
    for d in INCLUDE_DIRS:
        cand = resolve_rel(d, name)
        if os.path.isfile(cand):
            checked.add(os.path.realpath(cand))
            return cand
    # search libretro-common subdirs directly
    for d in LIBRETRO_COMM_SUBDIRS:
        cand = os.path.join(d, name)
        if os.path.isfile(cand):
            checked.add(os.path.realpath(cand))
            return cand
    return None

def scan():
    src_files = []
    for base in (MELON_DIR, CORE_DIR):
        for dirpath, dirnames, filenames in os.walk(base):
            # skip libretro-common internals already covered, but still fine
            if ".git" in dirpath:
                continue
            for fn in filenames:
                if fn.endswith(src_exts):
                    src_files.append((dirpath, fn))
    for dirpath, fn in src_files:
        fp = os.path.join(dirpath, fn)
        try:
            with open(fp, "r", encoding="utf-8", errors="replace") as f:
                content = f.read()
        except Exception:
            continue
        # process includes sequentially is not trivial (ifdefs). Just report raw includes that cannot be found.
        for m in inc_re.finditer(content):
            name = m.group(1).strip()
            # angle includes often from standard; everything quoted relative to file
            resolved = None
            # first try relative to the including file's directory
            rel = resolve_rel(dirpath, name)
            if os.path.isfile(rel):
                resolved = rel
                checked.add(os.path.realpath(rel))
            else:
                found = find_header(name)
                if found is not None:
                    resolved = found
            if resolved is None:
                # handle teakra<> includes expansion
                key = (name, None)
                missing = missing_quote if m.group(0).find('"') != -1 else missing_angle
                missing.setdefault(name, set()).add(fp)

    print("=== MISSING (cannot resolve) QUOTE includes ===")
    for k in sorted(missing_quote):
        print(k)
        for f in sorted(missing_quote[k])[:3]:
            print("    as in: " + f)
    print("=== MISSING (cannot resolve) ANGLE includes ===")
    for k in sorted(missing_angle):
        print(k)
        for f in sorted(missing_angle[k])[:3]:
            print("    as in: " + f)

if __name__ == "__main__":
    scan()