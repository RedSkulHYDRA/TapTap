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
    private val motionThreshold: Float = 0.8f
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
    }

    /**
     * Runnable that executes ONLY when the device has been still for 30s.
     * It disables gesture detection to save power.
     */
    private val stationaryRunnable = Runnable {
        if (!isStationary) {
            Log.d(TAG, "Timeout reached: Device is stationary. Blocking gestures.")
            isStationary = true
            notifyListeners() // Updates the Service: Gate is now BLOCKED
        }
    }

    override fun onActivate() {
        Log.d(TAG, "Activating Table Gate")
        this.handlerThread = HandlerThread("TableGateThread")
        this.handlerThread.start()
        this.handler = Handler(this.handlerThread.looper)

        // OPTIMIZATION: Start in "Unblocked" state so gestures work immediately
        isStationary = false
        isFirstReading = true
        motionHistory.clear()

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL, handler)

        // Start the countdown immediately. If the user doesn't move the phone,
        // it will block itself in 30 seconds.
        handler.postDelayed(stationaryRunnable, STATIONARY_TIMEOUT_MS)
    }

    override fun onDeactivate() {
        Log.d(TAG, "Deactivating Table Gate")
        sensorManager.unregisterListener(this)

        // Cleanup callbacks to prevent memory leaks
        if (::handler.isInitialized) {
            handler.removeCallbacks(stationaryRunnable)
        }

        handlerThread.quitSafely()
        motionHistory.clear()
        isFirstReading = true
    }

    override fun isBlocked(): Boolean {
        // Returns TRUE (Blocked) when stationary to disable gestures
        return isStationary
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return

        // --- 1. Calculate Raw Motion ---
        val motion = if (isFirstReading) {
            lastAccelValues = values.clone()
            isFirstReading = false
            0f
        } else {
            val deltaX = values[0] - lastAccelValues[0]
            val deltaY = values[1] - lastAccelValues[1]
            val deltaZ = values[2] - lastAccelValues[2]

            lastAccelValues = values.clone()
            sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)
        }

        // --- 2. Smooth the Data ---
        motionHistory.add(motion)
        if (motionHistory.size > MOTION_HISTORY_SIZE) {
            motionHistory.removeFirst()
        }
        smoothedMotion = if (motionHistory.isNotEmpty()) motionHistory.average().toFloat() else 0f

        // --- 3. State Machine Logic ---

        if (smoothedMotion > motionThreshold) {
            // === MOTION DETECTED (Device moved) ===

            // A. Cancel the "Go to Sleep" timer immediately
            // (Using hasCallbacks check to avoid unnecessary object creation/overhead)
            if (handler.hasCallbacks(stationaryRunnable)) {
                handler.removeCallbacks(stationaryRunnable)
            }

            // B. If we were blocked (stationary), WAKE UP immediately
            if (isStationary) {
                Log.d(TAG, "Motion detected ($smoothedMotion) > Threshold. Unblocking.")
                isStationary = false
                notifyListeners() // Updates the Service: Gate is now OPEN
            }
        } else {
            // === NO MOTION (Device still) ===

            // We only start the timer if:
            // 1. We are currently "Awake" (!isStationary)
            // 2. The timer isn't ALREADY running (!hasCallbacks)
            // This prevents the infinite reset bug.
            if (!isStationary && !handler.hasCallbacks(stationaryRunnable)) {
                Log.v(TAG, "Motion stopped. Scheduling sleep in 30s.")
                handler.postDelayed(stationaryRunnable, STATIONARY_TIMEOUT_MS)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
}