package com.lm.player.core.model

/**
 * 运行平台模式。
 *
 * - [TV]：面向智能电视 / 机顶盒，完整启用遥控器 D-Pad 焦点光环、方向键导航与菜单键交互。
 * - [CAR]：面向车载中控横屏大屏，以**触控**为输入方式，停用整套遥控器焦点体系
 *   （焦点光环不绘制、方向键与确定键不可进入），以降低车机端持续绘制焦点环与弹簧动画的资源占用。
 *
 * 由首次启动的双卡片向导强制选择，之后可在「设置 → 播放与启动行为 → 使用平台模式」中随时切换。
 */
enum class PlatformMode(val key: String, val displayName: String) {
    TV("TV", "电视 / 机顶盒"),
    CAR("CAR", "车机 / 中控");

    companion object {
        /** 未选择或无法识别时返回 null，用于区分「尚未选择」与任何一种已选模式。 */
        fun fromKey(key: String?): PlatformMode? =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/** 平台模式在 `lemon_settings_prefs` 中的存储键名。 */
const val PLATFORM_MODE_PREF_KEY = "platform_mode"
