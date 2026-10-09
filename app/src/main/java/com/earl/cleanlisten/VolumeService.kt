package com.earl.cleanlisten

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Does nothing except turn on Android's separate "accessibility volume". */
class VolumeService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onServiceConnected() { State.volumeServiceOn = true }
    override fun onDestroy() { State.volumeServiceOn = false; super.onDestroy() }
}
