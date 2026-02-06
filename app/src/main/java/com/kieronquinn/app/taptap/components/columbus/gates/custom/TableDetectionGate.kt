package com.kieronquinn.app.taptap.components.columbus.gates.custom

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import com.kieronquinn.app.taptap.components.columbus.gates.TapTapGate
import java.util.*
import kotlin.math.sqrt

class TableDetectionGate(
    serviceLifecycle: Lifecycle,
    context: Context,
    private val motionThreshold: Float = 1.0f
) : TapTapGate(serviceLifecycle, context), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // State flags
    private var isStationary = false

    // Threading
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    // Motion calculations
    private var lastAccelValues = FloatArray(3) { 0f }
    private var isFirstReading = true

    // Smoothing queue
    private val motionHistory = ArrayDeque<Float>(MOTION_HISTORY_SIZE)
    private var smoothedMotion = 0f

    companion object {
        private const val TAG = "TableDetectionGate"
        private const val MOTION_HISTORY_SIZE = 5
        private const val STATIONARY_TIMEOUT_MS = 30000L // 30 Seconds
        private const val SAMPLE_RATE_MICROS = 1_000_000 // 1Hz
    }

    private val stationaryRunnable = Runnable {
        if (!isStationary) {
            Log.d(TAG, "Timeout reached: Device is stationary. Blocking gestures.")
            isStationary = true
            notifyListeners()
        }
    }

    override fun onActivate() {
        Log.d(TAG, "Activating Table Gate")
        handlerThread = HandlerThread("TableGateThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        isStationary = false
        isFirstReading = true
        motionHistory.clear()
        smoothedMotion = 0f // ADDED: Reset smoothed value

        sensorManager.registerListener(this, accelerometer, SAMPLE_RATE_MICROS, handler)
        handler.postDelayed(stationaryRunnable, STATIONARY_TIMEOUT_MS)
    }

    override fun onDeactivate() {
        Log.d(TAG, "Deactivating Table Gate")
        sensorManager.unregisterListener(this)

        if (::handler.isInitialized) {
            handler.removeCallbacksAndMessages(null) // CHANGED: Clear all callbacks at once
        }

        if (::handlerThread.isInitialized) {
            handlerThread.quitSafely()
        }

        motionHistory.clear()
        isFirstReading = true
        smoothedMotion = 0f // ADDED: Reset state
    }

    override fun isBlocked(): Boolean {
        return isStationary
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return

        // Calculate motion delta
        val motion = if (isFirstReading) {
            lastAccelValues = values.copyOf() // CHANGED: copyOf() is more idiomatic
            isFirstReading = false
            0f
        } else {
            val deltaX = values[0] - lastAccelValues[0]
            val deltaY = values[1] - lastAccelValues[1]
            val deltaZ = values[2] - lastAccelValues[2]
            lastAccelValues = values.copyOf()
            sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)
        }

        // Smooth the data
        motionHistory.add(motion)
        if (motionHistory.size > MOTION_HISTORY_SIZE) {
            motionHistory.removeFirst()
        }
        smoothedMotion = motionHistory.average().toFloat()

        // State machine
        if (smoothedMotion > motionThreshold) {
            // Motion detected
            handler.removeCallbacks(stationaryRunnable)

            if (isStationary) {
                Log.d(TAG, "Motion detected ($smoothedMotion). Unblocking.")
                isStationary = false
                notifyListeners()
            }
        } else {
            // No motion - schedule sleep if not already scheduled
            if (!isStationary && !handler.hasCallbacks(stationaryRunnable)) {
                Log.v(TAG, "Motion stopped. Scheduling sleep in 30s.")
                handler.postDelayed(stationaryRunnable, STATIONARY_TIMEOUT_MS)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
}