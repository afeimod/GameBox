#include "ARMJIT_Global.h"
#include "ARMJIT_Memory.h"

#ifdef _WIN32
#include <windows.h>
#else
#include <sys/mman.h>
#include <unistd.h>
#endif

#include <stdio.h>
#include <stdint.h>

#include <mutex>

namespace melonDS
{

namespace ARMJIT_Global
{

std::mutex globalMutex;

#if defined(__APPLE__) && defined(__aarch64__)
#define APPLE_AARCH64
#endif

#if !defined(APPLE_AARCH64) && !defined(__NetBSD__) && !defined(__OpenBSD__)
static constexpr size_t NumCodeMemSlices = 4;
static constexpr size_t CodeMemoryAlignedSize = NumCodeMemSlices * CodeMemorySliceSize;

// I haven't heard of pages larger than 16 KB
u8 CodeMemory[CodeMemoryAlignedSize + 16*1024];

u32 AvailableCodeMemSlices = (1 << NumCodeMemSlices) - 1;

u8* GetAlignedCodeMemoryStart()
{
    return reinterpret_cast<u8*>((reinterpret_cast<intptr_t>(CodeMemory) + (16*1024-1)) & ~static_cast<intptr_t>(16*1024-1));
}
#endif

int RefCounter = 0;

void* AllocateCodeMem()
{
    std::lock_guard guard(globalMutex);

#if !defined(APPLE_AARCH64) && !defined(__NetBSD__) && !defined(__OpenBSD__)
    if (AvailableCodeMemSlices)
    {
        int slice = __builtin_ctz(AvailableCodeMemSlices);
        AvailableCodeMemSlices &= ~(1 << slice);
        //printf("allocating slice %d\n", slice);
        return &GetAlignedCodeMemoryStart()[slice * CodeMemorySliceSize];
    }
#endif

    // allocate
#ifdef _WIN32
    return VirtualAlloc(nullptr, CodeMemorySliceSize, MEM_RESERVE|MEM_COMMIT, PAGE_EXECUTE_READWRITE);
#elif defined(APPLE_AARCH64)
    return mmap(NULL, CodeMemorySliceSize, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS | MAP_JIT,-1, 0);
#elif defined(__NetBSD__)
    return mmap(nullptr, CodeMemorySliceSize, PROT_MPROTECT(PROT_READ | PROT_WRITE | PROT_EXEC), MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
#else
    //printf("mmaping...\n");
    return mmap(nullptr, CodeMemorySliceSize, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
#endif
}

void FreeCodeMem(void* codeMem)
{
    std::lock_guard guard(globalMutex);

#if !defined(APPLE_AARCH64) && !defined(__NetBSD__) && !defined(__OpenBSD__)
    for (int i = 0; i < NumCodeMemSlices; i++)
    {
        if (codeMem == &GetAlignedCodeMemoryStart()[CodeMemorySliceSize * i])
        {
            //printf("freeing slice\n");
            AvailableCodeMemSlices |= 1 << i;
            return;
        }
    }
#endif

#ifdef _WIN32
    VirtualFree(codeMem, CodeMemorySliceSize, MEM_RELEASE|MEM_DECOMMIT);
#else
    munmap(codeMem, CodeMemorySliceSize);
#endif
}

void Init()
{
    std::lock_guard guard(globalMutex);

    RefCounter++;
    if (RefCounter == 1)
    {
        #ifdef _WIN32
            DWORD dummy;
            VirtualProtect(GetAlignedCodeMemoryStart(), CodeMemoryAlignedSize, PAGE_EXECUTE_READWRITE, &dummy);
        #elif defined(APPLE_AARCH64) || defined(__NetBSD__) || defined(__OpenBSD__)
            // Apple aarch64 always uses dynamic allocation
        #else
            mprotect(GetAlignedCodeMemoryStart(), CodeMemoryAlignedSize, PROT_EXEC | PROT_READ | PROT_WRITE);
        #endif

        ARMJIT_Memory::RegisterFaultHandler();
    }
}

void DeInit()
{
    std::lock_guard guard(globalMutex);

    RefCounter--;
    if (RefCounter == 0)
    {
        ARMJIT_Memory::UnregisterFaultHandler();
    }
}

bool ProbeCodeMemory()
{
    // Lightweight capability check for the JIT recompiler: verify that this
    // process may make a page of its own (anonymous) memory executable.
    // Vanilla melonDS relies on exactly this when it mprotect()s the static
    // CodeMemory pool RWX in Init(); on some OEM builds / hardened SELinux
    // policies that call fails with EPERM, which historically manifested as
    // "JIT enabled but games are slow/crashy" with no diagnostic at all.
    //
    // The probe mirrors Init()'s allocation model WITHOUT touching the real
    // code-memory slices or the fault handler, so it is safe to call before
    // an NDS instance is constructed.
#if defined(_WIN32)
    // Desktop Windows always allows VirtualAlloc RWX; the libretro Android
    // build never takes this path, but keep the function total anyway.
    return true;
#elif defined(APPLE_AARCH64) || defined(__NetBSD__) || defined(__OpenBSD__)
    // Handled by their respective JIT support paths; assume usable here as
    // upstream does.
    return true;
#else
    long pagesz = sysconf(_SC_PAGESIZE);
    if (pagesz <= 0 || pagesz > 65536)
        pagesz = 4096;

    // Double-size buffer so we can always find a page-aligned address inside
    // our own data segment regardless of the runtime page size.
    static uint8_t probeBuf[131072] __attribute__((aligned(4096)));

    uintptr_t base = reinterpret_cast<uintptr_t>(probeBuf);
    base = (base + (uintptr_t)pagesz - 1) & ~((uintptr_t)pagesz - 1);

    void* page = reinterpret_cast<void*>(base);
    if (mprotect(page, (size_t)pagesz, PROT_READ | PROT_WRITE | PROT_EXEC) != 0)
        return false;

    // Restore — the page belongs to a plain static buffer, not code memory.
    mprotect(page, (size_t)pagesz, PROT_READ | PROT_WRITE);
    return true;
#endif
}

}

}
