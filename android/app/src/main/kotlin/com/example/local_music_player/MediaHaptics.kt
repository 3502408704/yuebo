package com.example.local_music_player

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 媒体控制专用触感反馈。
 *
 * 为什么用 [HapticFeedbackConstants.CLOCK_TICK]/[HapticFeedbackConstants.KEYBOARD_TAP]：
 * 两者都是系统级**单发短振**（毫秒级、无拖尾），松手即停——正是「短促按键手感」；
 * 且 `performHapticFeedback` 默认遵循系统「触摸振动」开关，用户关闭触感时静默，
 * 不需要自己维护开关。调用无分配、无挂起，不占按键链路（先振后执行，零延迟）。
 */
internal object MediaHaptics {
    /** 轻点：上/下一首、收藏等次级操作。 */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** 稍实的确认感：播放/暂停等主操作（仍是短促单击，非长振）。 */
    fun tap(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}
