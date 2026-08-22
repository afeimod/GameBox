#!/usr/bin/env python3
"""精确检查 CMakeLists.txt 中实际参与编译的源文件的 #include 是否都能解析."""
import os, re, sys

ROOT = "/data/data/com.termux/files/home/my-project"
MELON_SRC = ROOT + "/core/melonds_temp/melonDS-android-lib-master/src"
CORE_DIR = ROOT + "/core/melonds/libretro"
LRC = ROOT + "/core/melonds/libretro/libretro-common"
OLD_SRC = ROOT + "/core/melonds/src"

INCLUDE_DIRS = [
    os.path.join(LRC, "include"),
    CORE_DIR,
    MELON_SRC,
    os.path.join(MELON_SRC, "teakra/include"),
    os.path.join(MELON_SRC, "teakra/src"),
    os.path.join(MELON_SRC, "frontend"),
    os.path.join(MELON_SRC, "ARMJIT_A64"),
    os.path.join(MELON_SRC, "dolphin"),
    OLD_SRC,
]

LRC_SUBDIRS = [os.path.join(LRC, d) for d in os.listdir(LRC) if os.path.isdir(os.path.join(LRC, d))]

SYSTEM_HEADERS = set("""string.h stdlib.h stdio.h stdint.h stdarg.h stddef.h stdbool.h math.h time.h limits.h float.h
ctype.h assert.h errno.h fcntl.h unistd.h pthread.h signal.h inttypes.h stdalign.h malloc.h wchar.h strings.h
sys/types.h sys/stat.h sys/time.h sys/mman.h sys/utsname.h sys/socket.h sys/ioctl.h sys/param.h sys/un.h
sys/select.h sys/wait.h sys/syscall.h netinet/in.h arpa/inet.h endian.h byteswap.h jni.h android/log.h
EGL/egl.h EGL/eglext.h GLES2/gl2.h GLES2/gl2ext.h GLES2/gl2platform.h GLES3/gl3.h GL/GL.h
new typeinfo cstring cstdio cstdlib cstdint cstddef cmath cassert cctype climits cfloat cstdarg ctime
algorithm array atomic bitset chrono condition_variable deque exception forward_list fstream functional future
initializer_list iomanip ios iosfwd iostream istream iterator limits list locale map memory memory_resource
mutex numeric optional ostream queue random ratio regex set shared_mutex sstream stack stdexcept streambuf
string string_view system_error thread tuple type_traits typeindex unordered_map unordered_set utility
valarray vector variant version cinttypes csignal cwchar cwctype stdatomic.h stdnoreturn.h uchar.h
complex.h tgmath.h sys/eventfd.h sys/epoll.h poll.h libretro.h
EGL/eglplatform.h KHR/khrplatform.h GL/glext.h GL3/gl3.h GL3/gl3ext.h GLES/gl.h GLES/glext.h
OpenGL/gl.h OpenGL/gl3.h OpenGL/gl3ext.h OpenGL/glext.h OpenGLES/ES2/gl.h OpenGLES/ES2/glext.h
OpenGLES/ES3/gl.h OpenGLES/ES3/glext.h""".split())

inc_re = re.compile(r'#\s*include\s*[<"]\s*([^>"]+?)\s*[>"]')

def load_sources(cmake_path):
    """从 CMakeLists.txt 提取源文件列表(包括条件的简化处理)。"""
    with open(cmake_path, encoding="utf-8") as f:
        content = f.read()
    # 提取所有 .c/.cpp/.S 路径字面量
    srcs = set()
    for m in re.finditer(r'"((?:[^"\\]|\\.)+?\.(?:c|cpp|S))"', content):
        path = m.group(1).replace("${MELON_DIR}", MELON_SRC).replace("${CORE_DIR}", CORE_DIR).replace("${LIBRETRO_COMM}", LRC)
        srcs.add(path)
    return srcs

def find_header(name):
    if name in SYSTEM_HEADERS:
        return "system"
    for d in INCLUDE_DIRS:
        cand = os.path.normpath(os.path.join(d, name))
        if os.path.isfile(cand):
            return cand
    for d in LRC_SUBDIRS:
        cand = os.path.normpath(os.path.join(d, name))
        if os.path.isfile(cand):
            return cand
    # 也检查 include 子目录
    for d in INCLUDE_DIRS:
        for root2, dirs2, files2 in os.walk(d):
            if os.path.basename(name) in files2:
                return os.path.join(root2, name)
    return None

def main():
    srcs = load_sources(ROOT + "/core/melonds/CMakeLists.txt")
    print(f"共 {len(srcs)} 个源文件")
    missing = {}
    ok = 0
    for src in sorted(srcs):
        if not os.path.isfile(src):
            print(f"!! 源文件不存在: {src}")
            continue
        try:
            with open(src, encoding="utf-8", errors="replace") as f:
                content = f.read()
        except Exception:
            continue
        dirp = os.path.dirname(src)
        for m in inc_re.finditer(content):
            name = m.group(1).strip()
            # 优先相对本文件目录
            rel = os.path.normpath(os.path.join(dirp, name))
            if os.path.isfile(rel):
                ok += 1
                continue
            res = find_header(name)
            if res is not None:
                ok += 1
                continue
            missing.setdefault(name, set()).add(os.path.relpath(src, ROOT))
    print(f"\n== 无法解析的 include ({len(missing)}) ==")
    for k in sorted(missing):
        print(k, "  <=", sorted(missing[k]))

if __name__ == "__main__":
    main()