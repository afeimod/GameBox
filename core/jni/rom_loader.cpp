// Placeholder ROM loader. The real implementation will wrap FCEUmm's
// FCEUI_LoadGame / FCEUI_Emulate / FCEUI_GetCurrentVidFrame APIs.
#include "rom_loader.h"
#include <android/log.h>
#include <cstring>

#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "nescore-rom", __VA_ARGS__)

namespace nescore::rom {

static bool s_loaded = false;
static int  s_region = 0;
static float s_speed = 1.0f;
static int   s_rate  = 44100;
static int64_t s_frame = 0;

std::string loadFromFile(const std::string& path, int& regionOut) {
    // TODO: FCEUI_LoadGame path
    s_loaded = true;
    regionOut = 0;
    s_region = 0;
    s_frame = 0;
    return ""; // empty == success
}

void unload() {
    s_loaded = false;
    s_frame = 0;
}

void resetEmulation(bool /*hard*/) {
    s_frame = 0;
}

void applyRegion(int region) { s_region = region; }
void applySampleRate(int hz) { s_rate = hz; }
void applySpeed(float m) { s_speed = m; }

void stepFrame() {
    if (!s_loaded) return;
    s_frame++;
}

void copyFramebufferBGRA(uint8_t* out, int w, int h) {
    // Animated gradient placeholder so dev builds have something to see.
    // Real impl: FCEUI_GetCurrentVidFrame(&data) -> convert XBuf8 to BGRA.
    uint8_t phase = (uint8_t)(s_frame * 2);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            uint8_t* p = out + (y * w + x) * 4;
            p[0] = (uint8_t)(40 + (x * 255 / w));   // B
            p[1] = (uint8_t)(20 + (y * 100 / h));   // G
            p[2] = phase;                           // R
            p[3] = 0xFF;                            // A
        }
    }
}

void mixAudio(int16_t* out, int frames) {
    // Silence placeholder; real impl drives FCEUI_Emulate to fill SoundBuf.
    std::memset(out, 0, frames * 2 * sizeof(int16_t));
}

void setControllerInput(int /*port*/, uint8_t /*bits*/) { /* wired into FCEU */ }
void saveStateToPath(int /*slot*/, const std::string& /*path*/) {}
bool loadStateFromPath(int /*slot*/, const std::string& /*path*/) { return true; }

} // namespace nescore::rom
