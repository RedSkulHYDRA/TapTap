package com.kieronquinn.app.taptap.components.columbus.gates.custom

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import com.kieronquinn.app.taptap.components.columbus.gates.PassiveGate
import com.kieronquinn.app.taptap.components.columbus.gates.TapTapGate

class PocketDetectionGate(
    serviceLifecycle: Lifecycle,
    context: Context
) : TapTapGate(serviceLifecycle, context), PassiveGate, SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    // Volatile ensures immediate visibility across threads if called from different contexts
    @Volatile
    private var isNear = false

    companion object {
        private const val TAG = "PocketDetectionGate"
        // Proximity sensors are interrupt-based; SENSOR_DELAY_NORMAL is efficient
        // and ensures we get the "Far" event immediately after pulling out of pocket.
        private const val SENSOR_DELAY = SensorManager.SENSOR_DELAY_NORMAL
    }

    override fun onActivate() {
        Log.d(TAG, "Activating Pocket Detection Gate")

        if (proximitySensor == null) {
            Log.w(TAG, "No proximity sensor available")
            return
        }

        // Reset state on activation
        isNear = false

        // Register on the current thread (Service/Main Looper).
        // Proximity events are rare (only on change), so this will not lag the UI.
        sensorManager.registerListener(this, proximitySensor, SENSOR_DELAY)
    }

    override fun onDeactivate() {
        Log.d(TAG, "Deactivating Pocket Detection Gate")
        sensorManager.unregisterListener(this)

        // Reset state so we don't block anything if the gate is disabled
        isNear = false
    }

    override fun isBlocked(): Boolean {
        return isNear
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        val currentDistance = values[0]
        val maxRange = proximitySensor?.maximumRange ?: 5f // Fallback safe range

        val wasNear = isNear

        // Standard Android check: Near if distance < maxRange
        // Most sensors binary switch between 0.0 and maxRange.
        isNear = currentDistance < maxRange

        if (wasNear != isNear) {
            Log.d(TAG, "Proximity changed: ${if (isNear) "NEAR (blocked)" else "FAR (unblocked)"}")
            notifyListeners()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op for proximity
    }
}