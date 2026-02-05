package com.kieronquinn.app.taptap.components.columbus.gates.custom

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.kieronquinn.app.taptap.components.accessibility.TapTapAccessibilityRouter
import com.kieronquinn.app.taptap.components.columbus.gates.PassiveGate
import com.kieronquinn.app.taptap.components.columbus.gates.TapTapGate
import com.kieronquinn.app.taptap.utils.extensions.isKeyboardOpen
import com.kieronquinn.app.taptap.utils.extensions.whenCreated
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.core.component.inject

class KeyboardVisibilityGate(
    serviceLifecycle: Lifecycle,
    context: Context
) : TapTapGate(serviceLifecycle, context), PassiveGate {

    private val accessibilityRouter by inject<TapTapAccessibilityRouter>()

    private val keyboardVisible = accessibilityRouter.accessibilityOutputBus
        .filter { it is TapTapAccessibilityRouter.AccessibilityOutput.KeyboardVisibilityState }
        .map { (it as TapTapAccessibilityRouter.AccessibilityOutput.KeyboardVisibilityState).visible }
        .stateIn(lifecycleScope, SharingStarted.Eagerly, false)

    init {
        lifecycle.whenCreated {
            keyboardVisible.collect {
                notifyListeners()
            }
        }
    }

    override fun isBlocked(): Boolean {
        showAccessibilityNotificationIfNeeded()
        // Use the accessibility-detected state or fallback to the context method
        return keyboardVisible.value || context.isKeyboardOpen()
    }

}