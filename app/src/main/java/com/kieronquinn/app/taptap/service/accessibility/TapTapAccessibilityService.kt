package com.kieronquinn.app.taptap.service.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.kieronquinn.app.taptap.components.accessibility.TapTapAccessibilityRouter
import com.kieronquinn.app.taptap.utils.extensions.whenCreated
import com.kieronquinn.app.taptap.utils.lifecycle.LifecycleAccessibilityService
import kotlinx.coroutines.flow.filterNot
import org.koin.android.ext.android.inject

class TapTapAccessibilityService: LifecycleAccessibilityService() {

    companion object {
        private val EVENT_TYPES_SHADE_OPEN = arrayOf(
            AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED,
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED
        )
        private val EVENT_TYPES_SHADE_CLOSED = arrayOf(
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED
        )
    }

    private val router by inject<TapTapAccessibilityRouter>()
    private var currentPackageName = "android"

    private var isNotificationShadeOpen = false
    private var isQuickSettingsOpen = false
    private var isKeyboardVisible = false

    private val notificationShadeAccessibilityDesc by lazy {
        val default = "Notification shade."
        var value = default
        try{
            packageManager.getResourcesForApplication("com.android.systemui").run {
                value = getString(getIdentifier("accessibility_desc_notification_shade", "string", "com.android.systemui"))
            }
        }catch (e: Exception){}
        value
    }

    private val quickSettingsAccessibilityDesc by lazy {
        val default = "Quick settings."
        var value = default
        try{
            packageManager.getResourcesForApplication("com.android.systemui").run {
                value = getString(getIdentifier("accessibility_desc_quick_settings", "string", "com.android.systemui"))
            }
        }catch (e: Exception){}
        value
    }

    override fun onCreate() {
        super.onCreate()
        lifecycle.whenCreated {
            setupInputListener()
        }
        lifecycle.whenCreated {
            router.onAccessibilityStarted()
        }
    }

    private suspend fun setupInputListener() {
        router.accessibilityInputBus.filterNot {
            it is TapTapAccessibilityRouter.AccessibilityInput.GestureInput
        }.collect {
            handleInput(it)
        }
    }

    private fun handleInput(accessibilityInput: TapTapAccessibilityRouter.AccessibilityInput) {
        when(accessibilityInput) {
            is TapTapAccessibilityRouter.AccessibilityInput.PerformGlobalAction -> {
                performGlobalAction(accessibilityInput.globalActionId)
            }
            else -> {}
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if(event == null) return
        if(event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowStateChange(event)
        }
        if(event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            checkKeyboardVisibility()
        }
    }

    private fun handleWindowStateChange(event: AccessibilityEvent) {
        val isNotificationShade = event.text?.firstOrNull() == notificationShadeAccessibilityDesc
        val isQuickSettings = event.text?.firstOrNull() == quickSettingsAccessibilityDesc
        var currentPackageName = this.currentPackageName
        if(event.packageName?.toString() != currentPackageName) {
            if(event.packageName?.toString() != "android") {
                currentPackageName = event.packageName?.toString() ?: "android"
            }
        }
        lifecycle.whenCreated {
            if(this@TapTapAccessibilityService.currentPackageName != currentPackageName){
                this@TapTapAccessibilityService.currentPackageName = currentPackageName
                router.postOutput(TapTapAccessibilityRouter.AccessibilityOutput.AppOpen(currentPackageName))
            }
            if(isNotificationShade && EVENT_TYPES_SHADE_OPEN.contains(event.contentChangeTypes)){
                this@TapTapAccessibilityService.isNotificationShadeOpen = true
                router.postOutput(TapTapAccessibilityRouter.AccessibilityOutput.NotificationShadeState(true))
            }
            if(isNotificationShade && EVENT_TYPES_SHADE_CLOSED.contains(event.contentChangeTypes)){
                this@TapTapAccessibilityService.isNotificationShadeOpen = false
                router.postOutput(TapTapAccessibilityRouter.AccessibilityOutput.NotificationShadeState(false))
                router.postOutput(TapTapAccessibilityRouter.AccessibilityOutput.QuickSettingsShadeState(false))
            }
            if(isQuickSettings && EVENT_TYPES_SHADE_OPEN.contains(event.contentChangeTypes)){
                this@TapTapAccessibilityService.isQuickSettingsOpen = true
                router.postOutput(TapTapAccessibilityRouter.AccessibilityOutput.QuickSettingsShadeState(true))
            }
            if(isQuickSettings && EVENT_TYPES_SHADE_CLOSED.contains(event.contentChangeTypes)){
                this@TapTapAccessibilityService.isQuickSettingsOpen = false
                router.postOutput(TapTapAccessibilityRouter.AccessibilityOutput.QuickSettingsShadeState(false))
            }
        }
    }

    private fun checkKeyboardVisibility() {
        try {
            val windows = windows ?: return
            val isKeyboardVisible = windows.any { window ->
                window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }
            if(this.isKeyboardVisible != isKeyboardVisible) {
                this.isKeyboardVisible = isKeyboardVisible
                lifecycle.whenCreated {
                    router.postOutput(
                        TapTapAccessibilityRouter.AccessibilityOutput.KeyboardVisibilityState(isKeyboardVisible)
                    )
                }
            }
        } catch (e: Exception) {
        }
    }

    override fun onInterrupt() {
        //no-op
    }

}