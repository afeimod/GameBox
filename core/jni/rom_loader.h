#pragma once
#include <string>
#include <cstdint>

namespace nescore::rom {

std::string loadFromFile(const std::string& path, int& regionOut);
void unload();
void resetEmulation(bool hard);
void applyRegion(int region);
void applySampleRate(int hz);
void applySpeed(float multiplier);
void stepFrame();
void copyFramebufferBGRA(uint8_t* out, int w, int h);
void mixAudio(int16_t* out, int frames);
void setControllerInput(int port, uint8_t bits);
void saveStateToPath(int slot, const std::string& path);
bool loadStateFromPath(int slot, const std::string& path);

} // namespace nescore::rom
