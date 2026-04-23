package com.haostoo.wetypehookr

import android.view.HapticFeedbackConstants

enum class HapticType(val value: Int, val label: String) {

    NONE(0,"无震动"),
    KEYBOARD(HapticFeedbackConstants.KEYBOARD_TAP, "KEYBOARD_TAP"),
    VIRTUAL_KEY(HapticFeedbackConstants.VIRTUAL_KEY, "VIRTUAL_KEY"),
    VIRTUAL_KEY_RELEASE(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE,"VIRTUAL_KEY_RELEASE"),
    CLOCK(HapticFeedbackConstants.CLOCK_TICK, "CLOCK_TICK"),
    CONTEXT(HapticFeedbackConstants.CONTEXT_CLICK, "CONTEXT_CLICK"),
    LONG_PRESS(HapticFeedbackConstants.LONG_PRESS, "LONG_PRESS")
}