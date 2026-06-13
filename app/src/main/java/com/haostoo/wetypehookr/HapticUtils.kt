package com.haostoo.wetypehookr

import android.os.Build
import android.view.HapticFeedbackConstants

val HAPTIC_CONSTANTS = buildList {
    add(HapticFeedbackConstants.VIRTUAL_KEY)              // 0  Virtual Key
    add(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)      // 1  Virtual Key Release

    add(HapticFeedbackConstants.KEYBOARD_TAP)             // 2  Keyboard Tap
    add(if (Build.VERSION.SDK_INT >= 27)
        HapticFeedbackConstants.KEYBOARD_PRESS
    else HapticFeedbackConstants.KEYBOARD_TAP)            // 3  Keyboard Press
    add(if (Build.VERSION.SDK_INT >= 27)
        HapticFeedbackConstants.KEYBOARD_RELEASE
    else HapticFeedbackConstants.KEYBOARD_TAP)            // 4  Keyboard Release

    add(HapticFeedbackConstants.LONG_PRESS)               // 5  Long Press
    add(if (Build.VERSION.SDK_INT >= 23)
        HapticFeedbackConstants.CONTEXT_CLICK
    else HapticFeedbackConstants.LONG_PRESS)              // 6  Context Click
    add(if (Build.VERSION.SDK_INT >= 30)
        HapticFeedbackConstants.CONFIRM
    else HapticFeedbackConstants.VIRTUAL_KEY)             // 7  Confirm
    add(if (Build.VERSION.SDK_INT >= 30)
        HapticFeedbackConstants.REJECT
    else HapticFeedbackConstants.LONG_PRESS)              // 8  Reject

    add(if (Build.VERSION.SDK_INT >= 21)
        HapticFeedbackConstants.CLOCK_TICK
    else HapticFeedbackConstants.VIRTUAL_KEY)             // 9  Clock Tick

    add(if (Build.VERSION.SDK_INT >= 33)
        HapticFeedbackConstants.GESTURE_START
    else HapticFeedbackConstants.VIRTUAL_KEY)             // 10 Gesture Start
    add(if (Build.VERSION.SDK_INT >= 33)
        HapticFeedbackConstants.GESTURE_END
    else HapticFeedbackConstants.VIRTUAL_KEY)             // 11 Gesture End
    add(if (Build.VERSION.SDK_INT >= 34)
        HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
    else HapticFeedbackConstants.VIRTUAL_KEY)             // 12 Gesture Threshold Activate
    add(if (Build.VERSION.SDK_INT >= 34)
        HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
    else HapticFeedbackConstants.VIRTUAL_KEY)             // 13 Gesture Threshold Deactivate

    add(if (Build.VERSION.SDK_INT >= 30)
        HapticFeedbackConstants.DRAG_START
    else HapticFeedbackConstants.VIRTUAL_KEY)             // 14 Drag Start

    add(HapticFeedbackConstants.TEXT_HANDLE_MOVE)         // 15 Text Handle Move

    add(if (Build.VERSION.SDK_INT >= 34)
        HapticFeedbackConstants.SEGMENT_TICK
    else HapticFeedbackConstants.CLOCK_TICK)              // 16 Segment Tick
    add(if (Build.VERSION.SDK_INT >= 34)
        HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
    else HapticFeedbackConstants.CLOCK_TICK)              // 17 Segment Frequent Tick

    add(if (Build.VERSION.SDK_INT >= 34)
        HapticFeedbackConstants.TOGGLE_ON
    else HapticFeedbackConstants.VIRTUAL_KEY)             // 18 Toggle On
    add(if (Build.VERSION.SDK_INT >= 34)
        HapticFeedbackConstants.TOGGLE_OFF
    else HapticFeedbackConstants.VIRTUAL_KEY)             // 19 Toggle Off
}

fun hapticConstantForIndex(index: Int): Int =
    HAPTIC_CONSTANTS.getOrElse(index) { HapticFeedbackConstants.VIRTUAL_KEY }