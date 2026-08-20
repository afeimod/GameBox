package com.nesstation.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.PadLayout

/**
 * 核心设置子页：进入后展示该核心专属的模拟器选项。
 * 每个 DropdownRow 修改后通过 updateLayout 保存到 PadLayoutStore，
 * 并在游戏启动/运行时应用到对应核心引擎。
 */
@Composable
fun CoreSettingsPanel(
    platform: GamePlatform,
    padLayout: PadLayout,
    updateLayout: (PadLayout) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        when (platform) {
            GamePlatform.NES -> item {
                SettingsSection("FC / NES (FCEUmm)") {
                    DropdownRow("NTSC 滤镜",
                        listOf("disabled" to "关闭", "composite" to "复合", "svideo" to "S-Video",
                               "rgb" to "RGB", "monochrome" to "黑白"),
                        padLayout.ntscFilter
                    ) { updateLayout(padLayout.copy {ntscFilter = it}) }

                    DropdownRow("调色板",
                        listOf(
                            "default" to "默认", "asqrealc" to "AspiringSquire", "wii-vc" to "Wii VC",
                            "rgb" to "Nintendo RGB", "yuv-v3" to "FBX YUV-V3", "unsaturated-final" to "Unsaturated",
                            "sony-cxa2025as-us" to "Sony CXA", "pal" to "PAL", "bmf-final2" to "BMF Final 2",
                            "smooth-fbx" to "FBX Smooth", "composite-direct-fbx" to "FBX Composite",
                            "ntsc-hardware-fbx" to "FBX NTSC HW", "nes-classic-fbx" to "FBX NES Classic"
                        ),
                        padLayout.palette
                    ) { updateLayout(padLayout.copy {palette = it}) }

                    DropdownRow("裁剪过扫描",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.cropOverscan
                    ) { updateLayout(padLayout.copy {cropOverscan = it}) }

                    DropdownRow("区域",
                        listOf("Auto" to "自动", "NTSC" to "NTSC", "PAL" to "PAL", "Dendy" to "Dendy"),
                        padLayout.region
                    ) { updateLayout(padLayout.copy {region = it}) }

                    DropdownRow("超频(减少慢动作)",
                        listOf("disabled" to "关闭", "2x-Postrender" to "后渲染(兼容性好)", "2x-VBlank" to "VBlank(推荐·魂斗罗力量)"),
                        padLayout.overclocking
                    ) { updateLayout(padLayout.copy {overclocking = it}) }
                }
            }
            GamePlatform.SFC -> item {
                SettingsSection("SFC / SNES (Snes9x)") {
                    DropdownRow("画面比例",
                        listOf("4:3" to "4:3 (标准)", "uncorrected" to "8:7 (原始像素比)",
                               "auto" to "自动", "ntsc" to "NTSC", "pal" to "PAL"),
                        padLayout.aspectRatio
                    ) { updateLayout(padLayout.copy {aspectRatio = it}) }

                    DropdownRow("NTSC 滤镜",
                        listOf("disabled" to "关闭", "monochrome" to "黑白", "rf" to "RF",
                               "composite" to "复合", "s-video" to "S-Video", "rgb" to "RGB"),
                        padLayout.ntscFilter
                    ) { updateLayout(padLayout.copy {ntscFilter = it}) }

                    DropdownRow("裁剪过扫描",
                        listOf("enabled" to "开启", "disabled" to "关闭", "auto" to "自动"),
                        padLayout.sfcOverscan
                    ) { updateLayout(padLayout.copy {sfcOverscan = it}) }

                    DropdownRow("高分辨率模式",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.sfcGfxHires
                    ) { updateLayout(padLayout.copy {sfcGfxHires = it}) }

                    DropdownRow("透明效果",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.sfcGfxTransparency
                    ) { updateLayout(padLayout.copy {sfcGfxTransparency = it}) }

                    DropdownRow("图形裁剪",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.sfcGfxClip
                    ) { updateLayout(padLayout.copy {sfcGfxClip = it}) }

                    DropdownRow("允许无效VRAM访问",
                        listOf("disabled" to "开启 (允许)", "enabled" to "关闭 (禁止)"),
                        padLayout.sfcBlockInvalidVram
                    ) { updateLayout(padLayout.copy {sfcBlockInvalidVram = it}) }

                    DropdownRow("高分辨率混合",
                        listOf("disabled" to "关闭", "merge" to "合并", "blur" to "模糊"),
                        padLayout.sfcSideBySide
                    ) { updateLayout(padLayout.copy {sfcSideBySide = it}) }

                    DropdownRow("超频(SuperFX)",
                        listOf("100%" to "100% (默认)", "150%" to "150%", "200%" to "200%",
                               "300%" to "300%", "400%" to "400%", "500%" to "500%"),
                        padLayout.sfcOverclock
                    ) { updateLayout(padLayout.copy {sfcOverclock = it}) }

                    DropdownRow("减少精灵闪烁",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.sfcReduceSpriteFlicker
                    ) { updateLayout(padLayout.copy {sfcReduceSpriteFlicker = it}) }

                    DropdownRow("减少慢动作",
                        listOf("disabled" to "关闭", "light" to "轻微",
                               "compatible" to "兼容", "max" to "最大"),
                        padLayout.sfcReduceSlowdown
                    ) { updateLayout(padLayout.copy {sfcReduceSlowdown = it}) }

                    DropdownRow("音频插值",
                        listOf("gaussian" to "高斯(默认)", "cubic" to "三次", "sinc" to "Sinc",
                               "linear" to "线性", "none" to "无"),
                        padLayout.sfcAudioInterpolation
                    ) { updateLayout(padLayout.copy {sfcAudioInterpolation = it}) }

                    DropdownRow("回声缓冲Hack",
                        listOf("disabled" to "关闭", "enabled" to "开启(旧版Addmusic)"),
                        padLayout.sfcSoundOutput
                    ) { updateLayout(padLayout.copy {sfcSoundOutput = it}) }

                    DropdownRow("上下方向同时输入",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.sfcUpDownAllowed
                    ) { updateLayout(padLayout.copy {sfcUpDownAllowed = it}) }

                    DropdownRow("随机内存(不安全)",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.sfcSuperScope
                    ) { updateLayout(padLayout.copy {sfcSuperScope = it}) }

                    DropdownRow("BG图层 1",
                        listOf("enabled" to "显示", "disabled" to "隐藏"),
                        padLayout.sfcLayer1
                    ) { updateLayout(padLayout.copy {sfcLayer1 = it}) }

                    DropdownRow("BG图层 2",
                        listOf("enabled" to "显示", "disabled" to "隐藏"),
                        padLayout.sfcLayer2
                    ) { updateLayout(padLayout.copy {sfcLayer2 = it}) }

                    DropdownRow("BG图层 3",
                        listOf("enabled" to "显示", "disabled" to "隐藏"),
                        padLayout.sfcLayer3
                    ) { updateLayout(padLayout.copy {sfcLayer3 = it}) }

                    DropdownRow("BG图层 4",
                        listOf("enabled" to "显示", "disabled" to "隐藏"),
                        padLayout.sfcLayer4
                    ) { updateLayout(padLayout.copy {sfcLayer4 = it}) }

                    DropdownRow("精灵图层",
                        listOf("enabled" to "显示", "disabled" to "隐藏"),
                        padLayout.sfcLayer5
                    ) { updateLayout(padLayout.copy {sfcLayer5 = it}) }
                }
            }
            GamePlatform.GB, GamePlatform.GBA -> item {
                SettingsSection("GB / GBA (mGBA)") {
                    DropdownRow("主机型号",
                        listOf("Autodetect" to "自动", "Game Boy" to "Game Boy (DMG)",
                               "Super Game Boy" to "Super Game Boy", "Game Boy Color" to "Game Boy Color",
                               "Game Boy Advance" to "Game Boy Advance"),
                        padLayout.gbModel
                    ) { updateLayout(padLayout.copy {gbModel = it}) }

                    DropdownRow("SGB 边框",
                        listOf("ON" to "显示", "OFF" to "隐藏"),
                        padLayout.gbSgbBorders
                    ) { updateLayout(padLayout.copy {gbSgbBorders = it}) }

                    DropdownRow("GB色彩校正",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.gbColorCorrection
                    ) { updateLayout(padLayout.copy {gbColorCorrection = it}) }

                    DropdownRow("GB色彩预设",
                        listOf("default" to "默认", "AGB" to "GBA风格", "GB Pocket" to "Pocket风格",
                               "GB Light" to "亮色", "GB Original" to "原始"),
                        padLayout.gbcColorPreset
                    ) { updateLayout(padLayout.copy {gbcColorPreset = it}) }

                    DropdownRow("GBA色彩校正",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.gbaColorCorrection
                    ) { updateLayout(padLayout.copy {gbaColorCorrection = it}) }

                    DropdownRow("GBA色彩预设",
                        listOf("default" to "默认", "AGB" to "GBA原机", "GBA SP" to "GBA SP风格",
                               "GB Micro" to "GB Micro风格"),
                        padLayout.gbaColorPreset
                    ) { updateLayout(padLayout.copy {gbaColorPreset = it}) }

                    DropdownRow("帧混合",
                        listOf("OFF" to "关闭", "ON" to "开启", "fast" to "快速"),
                        padLayout.gbaFrameBlending
                    ) { updateLayout(padLayout.copy {gbaFrameBlending = it}) }

                    DropdownRow("音频重采样器",
                        listOf("nearest" to "最近邻(快速)", "sinc" to "Sinc(高质量)",
                               "cosine" to "余弦(均衡)", "cubic" to "三次(高质量)"),
                        padLayout.gbaAudioResampler
                    ) { updateLayout(padLayout.copy {gbaAudioResampler = it}) }

                    DropdownRow("低通滤波",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.gbaAudioLowPass
                    ) { updateLayout(padLayout.copy {gbaAudioLowPass = it}) }

                    DropdownRow("低通滤波范围",
                        listOf("20" to "20", "40" to "40", "60" to "60 (默认)",
                               "80" to "80", "100" to "100"),
                        padLayout.gbaAudioLowPassRange
                    ) { updateLayout(padLayout.copy {gbaAudioLowPassRange = it}) }

                    DropdownRow("跳帧类型",
                        listOf("disabled" to "关闭", "auto" to "自动跳帧", "fixed" to "固定跳帧"),
                        padLayout.gbaFrameskipType
                    ) { updateLayout(padLayout.copy {gbaFrameskipType = it}) }

                    DropdownRow("跳帧数量",
                        listOf("0" to "0", "1" to "1", "2" to "2", "3" to "3",
                               "4" to "4", "5" to "5", "6" to "6", "7" to "7",
                               "8" to "8", "9" to "9", "10" to "10"),
                        padLayout.gbaFrameskipCount
                    ) { updateLayout(padLayout.copy {gbaFrameskipCount = it}) }

                    DropdownRow("跳帧阈值(自动)",
                        listOf("10" to "10", "20" to "20", "33" to "33 (默认)",
                               "50" to "50", "70" to "70", "90" to "90"),
                        padLayout.gbaFrameskipThreshold
                    ) { updateLayout(padLayout.copy {gbaFrameskipThreshold = it}) }

                    DropdownRow("空闲优化",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.gbaIdleOptimization
                    ) { updateLayout(padLayout.copy {gbaIdleOptimization = it}) }

                    DropdownRow("允许相反方向",
                        listOf("OFF" to "关闭", "ON" to "开启"),
                        padLayout.gbaAllowOpposite
                    ) { updateLayout(padLayout.copy {gbaAllowOpposite = it}) }

                    DropdownRow("太阳能传感器",
                        listOf("0" to "0 (黑暗)", "1" to "1", "2" to "2", "3" to "3",
                               "4" to "4", "5" to "5 (中等)", "6" to "6", "7" to "7",
                               "8" to "8", "9" to "9", "10" to "10 (明亮)"),
                        padLayout.gbaSolarSensor
                    ) { updateLayout(padLayout.copy {gbaSolarSensor = it}) }

                    DropdownRow("强制RTC",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.gbaForceRTC
                    ) { updateLayout(padLayout.copy {gbaForceRTC = it}) }
                }
            }
            GamePlatform.MD -> item {
                SettingsSection("MD / SEGA (Genesis-Plus-GX)") {
                    DropdownRow("区域",
                        listOf("auto" to "自动", "ntsc-u" to "NTSC-U(美)",
                               "pal" to "PAL(欧)", "ntsc-j" to "NTSC-J(日)"),
                        padLayout.mdRegion
                    ) { updateLayout(padLayout.copy {mdRegion = it}) }

                    DropdownRow("系统型号",
                        listOf("auto" to "自动", "md" to "Mega Drive",
                               "sms" to "Master System", "gg" to "Game Gear", "sg" to "SG-1000"),
                        padLayout.mdSystem
                    ) { updateLayout(padLayout.copy {mdSystem = it}) }

                    DropdownRow("画面比例",
                        listOf("auto" to "自动", "4:3" to "4:3 (标准)",
                               "16:9" to "16:9", "stretch" to "全屏拉伸"),
                        padLayout.mdAspect
                    ) { updateLayout(padLayout.copy {mdAspect = it}) }

                    DropdownRow("渲染模式",
                        listOf("normal" to "普通", "double" to "双倍",
                               "interlaced" to "隔行扫描"),
                        padLayout.mdRender
                    ) { updateLayout(padLayout.copy {mdRender = it}) }

                    DropdownRow("NTSC滤镜",
                        listOf("disabled" to "关闭", "monochrome" to "黑白", "rf" to "RF",
                               "composite" to "复合", "s-video" to "S-Video", "rgb" to "RGB"),
                        padLayout.mdNtscFilter
                    ) { updateLayout(padLayout.copy {mdNtscFilter = it}) }

                    DropdownRow("LCD滤镜",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.mdLcdFilter
                    ) { updateLayout(padLayout.copy {mdLcdFilter = it}) }

                    DropdownRow("过扫描",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.mdOverscan
                    ) { updateLayout(padLayout.copy {mdOverscan = it}) }

                    DropdownRow("GG扩展屏幕",
                        listOf("disabled" to "关闭(原始160x144)", "enabled" to "开启(扩展256x144)"),
                        padLayout.mdGgExtra
                    ) { updateLayout(padLayout.copy {mdGgExtra = it}) }

                    DropdownRow("GG画面拉伸",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.mdGgStretch
                    ) { updateLayout(padLayout.copy {mdGgStretch = it}) }

                    DropdownRow("左侧边框",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.mdLeftBorder
                    ) { updateLayout(padLayout.copy {mdLeftBorder = it}) }

                    DropdownRow("手柄类型",
                        listOf("3 button" to "3键手柄(经典)", "6 button" to "6键手柄(街机)"),
                        padLayout.mdInput
                    ) { updateLayout(padLayout.copy {mdInput = it}) }

                    DropdownRow("允许上下同时输入",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.mdAllowUpDown
                    ) { updateLayout(padLayout.copy {mdAllowUpDown = it}) }

                    DropdownRow("超频",
                        listOf("100%" to "100%", "125%" to "125%",
                               "150%" to "150%", "200%" to "200%"),
                        padLayout.mdOverclock
                    ) { updateLayout(padLayout.copy {mdOverclock = it}) }

                    DropdownRow("跳帧",
                        listOf("0" to "0", "1" to "1", "2" to "2", "3" to "3", "4" to "4", "5" to "5"),
                        padLayout.mdFrameskip
                    ) { updateLayout(padLayout.copy {mdFrameskip = it}) }

                    DropdownRow("CD快速启动",
                        listOf("enabled" to "开启(跳过BIOS动画)", "disabled" to "关闭"),
                        padLayout.mdCdFastboot
                    ) { updateLayout(padLayout.copy {mdCdFastboot = it}) }

                    DropdownRow("FM音源",
                        listOf("auto" to "自动", "on" to "开启", "off" to "关闭"),
                        padLayout.mdSmsFm
                    ) { updateLayout(padLayout.copy {mdSmsFm = it}) }
                }
            }
            GamePlatform.PCE -> item {
                SettingsSection("PCE / TG16 (Geargrafx)") {
                    DropdownRow("主机型号",
                        listOf("Auto" to "自动", "PC Engine (JAP)" to "PC-Engine(日)",
                               "SuperGrafx (JAP)" to "SuperGrafx(日)",
                               "TurboGrafx-16 (USA)" to "TurboGrafx-16(美)"),
                        padLayout.pceConsoleType
                    ) { updateLayout(padLayout.copy {pceConsoleType = it}) }

                    DropdownRow("画面比例",
                        listOf("1:1 PAR" to "1:1 (像素方形)",
                               "4:3 DAR" to "4:3 (标准)",
                               "6:5 DAR" to "6:5",
                               "16:9 DAR" to "16:9", "16:10 DAR" to "16:10"),
                        padLayout.pceAspect
                    ) { updateLayout(padLayout.copy {pceAspect = it}) }

                    DropdownRow("过扫描",
                        listOf("Disabled" to "关闭", "Enabled" to "开启"),
                        padLayout.pceOverscan
                    ) { updateLayout(padLayout.copy {pceOverscan = it}) }

                    DropdownRow("精灵数限制",
                        listOf("Disabled" to "关闭(原始,可能有闪烁)", "Enabled" to "开启(消除闪烁)"),
                        padLayout.pceNoSpriteLimit
                    ) { updateLayout(padLayout.copy {pceNoSpriteLimit = it}) }

                    DropdownRow("调色板",
                        listOf("Standard RGB" to "标准RGB", "Turboxray" to "Turboxray", "Kitrinx" to "Kitrinx"),
                        padLayout.pcePalette
                    ) { updateLayout(padLayout.copy {pcePalette = it}) }

                    DropdownRow("允许上下同时输入",
                        listOf("Disabled" to "关闭", "Enabled" to "开启"),
                        padLayout.pceAllowUpDown
                    ) { updateLayout(padLayout.copy {pceAllowUpDown = it}) }

                    DropdownRow("TurboTap(5人多人)",
                        listOf("Disabled" to "关闭", "Enabled" to "开启"),
                        padLayout.pceTurbotap
                    ) { updateLayout(padLayout.copy {pceTurbotap = it}) }

                    DropdownRow("Memory Base 128",
                        listOf("Auto" to "自动", "Enabled" to "开启", "Disabled" to "关闭"),
                        padLayout.pceMb128
                    ) { updateLayout(padLayout.copy {pceMb128 = it}) }

                    DropdownRow("CD BIOS",
                        listOf("Auto" to "自动",
                               "System Card 1" to "System Card 1",
                               "System Card 2" to "System Card 2",
                               "System Card 3" to "System Card 3 (推荐)",
                               "Game Express" to "Games Express"),
                        padLayout.pceCdromBios
                    ) { updateLayout(padLayout.copy {pceCdromBios = it}) }
                }
            }
            GamePlatform.DOS -> item {
                SettingsSection("DOS (DOSBox-Pure)") {
                    DropdownRow("显示芯片",
                        listOf(
                            "svga_s3" to "SVGA (S3 Trio64, 推荐)",
                            "vgaonly" to "VGA Only",
                            "ega" to "EGA",
                            "cga" to "CGA",
                            "tandy" to "Tandy",
                            "pcjr" to "PCjr",
                            "hercules" to "Hercules",
                            "none" to "无(仅文本模式)"
                        ),
                        padLayout.dosMachine
                    ) { updateLayout(padLayout.copy {dosMachine = it}) }

                    DropdownRow("CPU 周期",
                        listOf(
                            "auto" to "自动(推荐)",
                            "max" to "最大",
                            "6000" to "6000 (80386)",
                            "10000" to "10000 (80486)",
                            "20000" to "20000 (Pentium)",
                            "40000" to "40000 (Pentium II)",
                            "80000" to "80000 (Pentium III)",
                            "custom" to "自定义"
                        ),
                        padLayout.dosCycles
                    ) { updateLayout(padLayout.copy {dosCycles = it}) }

                    if (padLayout.dosCycles == "custom") {
                        DropdownRow("自定义周期",
                            listOf("10000" to "10000", "20000" to "20000",
                                   "30000" to "30000", "50000" to "50000",
                                   "80000" to "80000", "100000" to "100000"),
                            padLayout.dosCyclesMax
                        ) { updateLayout(padLayout.copy {dosCyclesMax = it}) }
                    }

                    DropdownRow("声霸卡类型",
                        listOf(
                            "sb16" to "Sound Blaster 16 (推荐·默认)",
                            "sbpro2" to "Sound Blaster Pro 2",
                            "sbpro1" to "Sound Blaster Pro",
                            "sb2" to "Sound Blaster 2.0",
                            "none" to "关闭声音"
                        ),
                        padLayout.dosSbType
                    ) { updateLayout(padLayout.copy {dosSbType = it}) }

                    DropdownRow("鼠标输入模式",
                        listOf(
                            "touchpad" to "触控板(推荐·默认)",
                            "auto" to "自动",
                            "virtual" to "虚拟鼠标",
                            "direct" to "直接控制",
                            "off" to "关闭"
                        ),
                        padLayout.dosMouseInput
                    ) { updateLayout(padLayout.copy {dosMouseInput = it}) }

                    DropdownRow("鼠标超时",
                        listOf("off" to "关闭", "3" to "3秒", "5" to "5秒", "10" to "10秒"),
                        padLayout.dosMouseTimeout
                    ) { updateLayout(padLayout.copy {dosMouseTimeout = it}) }

                    DropdownRow("键盘布局",
                        listOf(
                            "us" to "US (美式)", "uk" to "UK (英式)",
                            "de" to "德语", "fr" to "法语", "it" to "意大利语",
                            "es" to "西班牙语", "br" to "巴西", "ru" to "俄语",
                            "jp" to "日语"
                        ),
                        padLayout.dosKeyboardLayout
                    ) { updateLayout(padLayout.copy {dosKeyboardLayout = it}) }

                    DropdownRow("按键延迟",
                        listOf("100" to "100ms", "200" to "200ms", "300" to "300ms",
                               "400" to "400ms", "500" to "500ms"),
                        padLayout.dosKeyboardDelay
                    ) { updateLayout(padLayout.copy {dosKeyboardDelay = it}) }

                    DropdownRow("按键重复率",
                        listOf("5" to "5/s", "10" to "10/s", "15" to "15/s",
                               "20" to "20/s", "30" to "30/s"),
                        padLayout.dosKeyboardRate
                    ) { updateLayout(padLayout.copy {dosKeyboardRate = it}) }

                    DropdownRow("分辨率",
                        listOf(
                            "original" to "原始(推荐)",
                            "640x480" to "640×480",
                            "800x600" to "800×600",
                            "1024x768" to "1024×768",
                            "1280x720" to "1280×720 (HD)",
                            "1600x900" to "1600×900 (HD+)",
                            "1920x1080" to "1920×1080 (FHD)",
                            "custom" to "自定义"
                        ),
                        padLayout.dosResolution
                    ) { updateLayout(padLayout.copy {dosResolution = it}) }

                    DropdownRow("缩放倍数",
                        listOf("1" to "1×", "2" to "2×", "3" to "3×", "4" to "4×", "5" to "5×"),
                        padLayout.dosScale
                    ) { updateLayout(padLayout.copy {dosScale = it}) }

                    DropdownRow("画面比例",
                        listOf("auto" to "自动", "4:3" to "4:3", "16:9" to "16:9",
                               "16:10" to "16:10", "stretch" to "拉伸"),
                        padLayout.dosAspectRatio
                    ) { updateLayout(padLayout.copy {dosAspectRatio = it}) }

                    DropdownRow("CGA 配色",
                        listOf("default" to "默认", "amber" to "琥珀色",
                               "green" to "绿色", "white" to "白色", "bright" to "高亮"),
                        padLayout.dosCgaColors
                    ) { updateLayout(padLayout.copy {dosCgaColors = it}) }

                    DropdownRow("自动键位映射",
                        listOf("on" to "开启(推荐)", "off" to "关闭"),
                        padLayout.dosAutoMapping
                    ) { updateLayout(padLayout.copy {dosAutoMapping = it}) }

                    DropdownRow("Voodoo 显卡",
                        listOf("off" to "关闭", "on" to "开启"),
                        padLayout.dosVoodoo
                    ) { updateLayout(padLayout.copy {dosVoodoo = it}) }

                    DropdownRow("强制 60fps",
                        listOf("on" to "开启(推荐)", "off" to "关闭"),
                        padLayout.dosForce60fps
                    ) { updateLayout(padLayout.copy {dosForce60fps = it}) }

                    DropdownRow("时间播报",
                        listOf("none" to "关闭", "boot" to "启动时", "quiet" to "静默"),
                        padLayout.dosTimeAnnounce
                    ) { updateLayout(padLayout.copy {dosTimeAnnounce = it}) }

                    DropdownRow("暗屏超时",
                        listOf("off" to "关闭", "5" to "5秒", "10" to "10秒",
                               "20" to "20秒", "30" to "30秒", "60" to "60秒"),
                        padLayout.dosDimScreen
                    ) { updateLayout(padLayout.copy {dosDimScreen = it}) }

                    DropdownRow("存档大小",
                        listOf("on" to "默认", "500" to "500MB", "1000" to "1GB",
                               "2000" to "2GB", "4000" to "4GB", "8000" to "8GB", "0" to "关闭"),
                        padLayout.dosSavestate
                    ) { updateLayout(padLayout.copy {dosSavestate = it}) }

                    DropdownRow("虚拟按键模式",
                        listOf("gamepad" to "手柄(圆形按钮)", "keyboard" to "全键盘(QWERTY)"),
                        padLayout.dosInputMode
                    ) { updateLayout(padLayout.copy {dosInputMode = it}) }
                }
            }
            GamePlatform.ARCADE -> item {
                SettingsSection("街机 (FBNeo)") {
                    DropdownRow("方向控制",
                        listOf("dpad" to "十字键 D-Pad", "analog" to "摇杆 Analog Stick"),
                        padLayout.arcadeInputMode
                    ) { updateLayout(padLayout.copy {arcadeInputMode = it}) }

                    DropdownRow("显示 L2/R2 按键",
                        listOf("false" to "关闭 (4键默认)", "true" to "开启 (6键格斗)"),
                        padLayout.arcadeShowL2R2.toString()
                    ) { updateLayout(padLayout.copy {arcadeShowL2R2 = it.toBoolean()}) }

                    DropdownRow("画面比例",
                        listOf("auto" to "自动", "4:3" to "4:3 (标准)",
                               "3:4" to "3:4 (竖屏)", "16:9" to "16:9", "16:15" to "16:15"),
                        padLayout.arcadeAspect
                    ) { updateLayout(padLayout.copy {arcadeAspect = it}) }

                    DropdownRow("画面旋转",
                        listOf("norotate" to "不旋转", "cw" to "顺时针90°",
                               "ccw" to "逆时针90°", "flip" to "翻转180°"),
                        padLayout.arcadeRotate
                    ) { updateLayout(padLayout.copy {arcadeRotate = it}) }

                    DropdownRow("竖屏模式",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.arcadeVerticalMode
                    ) { updateLayout(padLayout.copy {arcadeVerticalMode = it}) }

                    DropdownRow("裁剪过扫描",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.arcadeCropOverscan
                    ) { updateLayout(padLayout.copy {arcadeCropOverscan = it}) }

                    DropdownRow("CPU速度",
                        listOf("100" to "100%", "75" to "75%", "50" to "50%",
                               "150" to "150%", "200" to "200%", "250" to "250%"),
                        padLayout.arcadeCpuSpeed
                    ) { updateLayout(padLayout.copy {arcadeCpuSpeed = it}) }

                    DropdownRow("跳帧",
                        listOf("0" to "0", "1" to "1", "2" to "2", "3" to "3",
                               "4" to "4", "5" to "5", "6" to "6", "8" to "8", "10" to "10"),
                        padLayout.arcadeFrameskip
                    ) { updateLayout(padLayout.copy {arcadeFrameskip = it}) }

                    DropdownRow("强制60Hz",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.arcadeForce60hz
                    ) { updateLayout(padLayout.copy {arcadeForce60hz = it}) }

                    DropdownRow("采样率",
                        listOf("48000" to "48000 Hz", "44100" to "44100 Hz",
                               "22050" to "22050 Hz"),
                        padLayout.arcadeSampleRate
                    ) { updateLayout(padLayout.copy {arcadeSampleRate = it}) }

                    DropdownRow("音频插值",
                        listOf("0" to "关闭", "1" to "最近邻", "2" to "线性(推荐)", "3" to "三次"),
                        padLayout.arcadeAudioInterp
                    ) { updateLayout(padLayout.copy {arcadeAudioInterp = it}) }

                    DropdownRow("低通滤波",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.arcadeLowpass
                    ) { updateLayout(padLayout.copy {arcadeLowpass = it}) }

                    DropdownRow("NeoGeo模式",
                        listOf("MVS" to "MVS(街机)", "AES" to "AES(家用)"),
                        padLayout.arcadeNeogeomode
                    ) { updateLayout(padLayout.copy {arcadeNeogeomode = it}) }

                    DropdownRow("记忆卡",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.arcadeMemcard
                    ) { updateLayout(padLayout.copy {arcadeMemcard = it}) }
                }
            }
            GamePlatform.JAVA -> item {
                SettingsSection("Java (J2ME)") {
                    // J2ME 核心没有专属选项
                }
            }
            GamePlatform.NDS -> item {
                SettingsSection("NDS / DSi (melonDS)") {
                    DropdownRow("内置 BIOS (免 BIOS)",
                        listOf("enabled" to "开启(无需 BIOS 文件)", "disabled" to "关闭(需导入 BIOS)"),
                        padLayout.ndsUseFwBios
                    ) { updateLayout(padLayout.copy {ndsUseFwBios = it}) }
                    DropdownRow("主机模式",
                        listOf("ds" to "DS", "dsi" to "DSi"),
                        padLayout.ndsConsoleMode
                    ) { updateLayout(padLayout.copy {ndsConsoleMode = it}) }
                    DropdownRow("屏幕布局",
                        listOf("top_bottom" to "上下排列", "bottom_top" to "下上排列",
                               "left_right" to "左右排列", "right_left" to "右左排列",
                               "top_only" to "仅上方屏", "bottom_only" to "仅下方屏",
                               "turnscreen" to "旋转屏"),
                        padLayout.ndsScreenLayout
                    ) { updateLayout(padLayout.copy {ndsScreenLayout = it}) }
                    DropdownRow("渲染分辨率",
                        listOf("1" to "1x (原生)", "2" to "2x", "3" to "3x", "4" to "4x", "5" to "5x"),
                        padLayout.ndsResolution
                    ) { updateLayout(padLayout.copy {ndsResolution = it}) }
                    DropdownRow("OpenGL 过滤",
                        listOf("nearest" to "最近邻(锐利)", "linear" to "线性(平滑)"),
                        padLayout.ndsFiltering
                    ) { updateLayout(padLayout.copy {ndsFiltering = it}) }
                    DropdownRow("屏保",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.ndsScreensaver
                    ) { updateLayout(padLayout.copy {ndsScreensaver = it}) }
                    DropdownRow("触摸模式",
                        listOf("mouse" to "鼠标", "touch" to "触摸", "disabled" to "关闭"),
                        padLayout.ndsTouchMode
                    ) { updateLayout(padLayout.copy {ndsTouchMode = it}) }
                    DropdownRow("鼠标速度",
                        listOf("50" to "50%", "75" to "75%", "100" to "100%", "125" to "125%", "150" to "150%", "175" to "175%", "200" to "200%"),
                        padLayout.ndsMouseSpeed
                    ) { updateLayout(padLayout.copy {ndsMouseSpeed = it}) }
                    DropdownRow("DSi SD 卡",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.ndsDsiSdcard
                    ) { updateLayout(padLayout.copy {ndsDsiSdcard = it}) }
                    DropdownRow("随机 MAC 地址",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.ndsRandomizeMac
                    ) { updateLayout(padLayout.copy {ndsRandomizeMac = it}) }
                }
            }
            GamePlatform.PSX -> item {
                SettingsSection("PSX (PCSX-ReARMed)") {
                    DropdownRow("BIOS",
                        listOf("auto" to "自动", "HLE" to "HLE(无 BIOS)",
                               "scph1000" to "SCPH-1000(日)", "scph1001" to "SCPH-1001(美)",
                               "scph1002" to "SCPH-1002(欧)", "scph5500" to "SCPH-5500(日)",
                               "scph5501" to "SCPH-5501(美)", "scph5502" to "SCPH-5502(欧)",
                               "psxonpsp660" to "PSP-660"),
                        padLayout.pscxBios
                    ) { updateLayout(padLayout.copy {pscxBios = it}) }
                    DropdownRow("区域",
                        listOf("auto" to "自动", "ntsc" to "NTSC", "pal" to "PAL"),
                        padLayout.pscxRegion
                    ) { updateLayout(padLayout.copy {pscxRegion = it}) }
                    DropdownRow("跳帧类型",
                        listOf("disabled" to "关闭", "auto" to "自动", "fixed" to "固定"),
                        padLayout.pscxFrameskipType
                    ) { updateLayout(padLayout.copy {pscxFrameskipType = it}) }
                    DropdownRow("固定跳帧数",
                        listOf("1" to "1", "2" to "2", "3" to "3", "4" to "4", "5" to "5", "6" to "6", "8" to "8", "10" to "10"),
                        padLayout.pscxFrameskip
                    ) { updateLayout(padLayout.copy {pscxFrameskip = it}) }
                    DropdownRow("1P 手柄类型",
                        listOf("standard" to "标准(D-Pad)", "analog" to "模拟摇杆",
                               "negcon" to "NeGcon", "gun" to "光枪"),
                        padLayout.pscxPad1Type
                    ) { updateLayout(padLayout.copy {pscxPad1Type = it}) }
                    DropdownRow("2P 手柄类型",
                        listOf("standard" to "标准(D-Pad)", "analog" to "模拟摇杆",
                               "negcon" to "NeGcon", "gun" to "光枪"),
                        padLayout.pscxPad2Type
                    ) { updateLayout(padLayout.copy {pscxPad2Type = it}) }
                    DropdownRow("震动",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.pscxVibration
                    ) { updateLayout(padLayout.copy {pscxVibration = it}) }
                    DropdownRow("抖动",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.pscxDithering
                    ) { updateLayout(padLayout.copy {pscxDithering = it}) }
                    DropdownRow("SPU 插值",
                        listOf("simple" to "简单", "gaussian" to "高斯", "cubic" to "立方", "off" to "关闭"),
                        padLayout.pscxSpuInterp
                    ) { updateLayout(padLayout.copy {pscxSpuInterp = it}) }
                    DropdownRow("SPU 混响",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.pscxSpuReverb
                    ) { updateLayout(padLayout.copy {pscxSpuReverb = it}) }
                    DropdownRow("显示 BIOS 启动画面",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.pscxShowBootlogo
                    ) { updateLayout(padLayout.copy {pscxShowBootlogo = it}) }
                    DropdownRow("CD 预读扇区",
                        listOf("0" to "0", "4" to "4", "8" to "8", "12" to "12(默认)", "16" to "16", "20" to "20", "30" to "30"),
                        padLayout.pscxCdReadahead
                    ) { updateLayout(padLayout.copy {pscxCdReadahead = it}) }
                    DropdownRow("记忆卡 1",
                        listOf("libretro" to "Libretro", "shared" to "共享", "disabled" to "关闭"),
                        padLayout.pscxMemcard1
                    ) { updateLayout(padLayout.copy {pscxMemcard1 = it}) }
                    DropdownRow("记忆卡 2",
                        listOf("libretro" to "Libretro", "shared" to "共享", "disabled" to "关闭"),
                        padLayout.pscxMemcard2
                    ) { updateLayout(padLayout.copy {pscxMemcard2 = it}) }
                }
            }
        }
    }
}
