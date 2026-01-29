package com.kieronquinn.app.taptap.components.columbus.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects when the device is idle (no significant motion) to reduce battery drain
 * by pausing sensor listening during idle periods.
 *
 * Strategy:
 * - Uses accelerometer with SENSOR_DELAY_UI to monitor motion
 * - Considers device idle after 30 Seconds of no significant motion
 * - Resumes listening immediately when motion detected
 * - Threshold: 1 m/s² (small movements like table vibrations won't trigger)
 */
class IdleStateDetector(
    private val context: Context
) : SensorEventListener {

    companion object {
        private const val TAG = "IdleStateDetector"

        // Time without motion before considering device idle (30 seconds)
        private const val IDLE_TIMEOUT_MS = 30 * 1000L

        // Motion threshold in m/s² (excluding gravity)
        // 1 m/s² filters out small vibrations while catching deliberate movement
        private const val MOTION_THRESHOLD = 1f

        // Sampling interval for idle detection (10 seconds is sufficient)
        private const val IDLE_CHECK_INTERVAL_MS = 10000
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    private val _isIdle = MutableStateFlow(false)
    val isIdle: StateFlow<Boolean> = _isIdle.asStateFlow()

    private var lastMotionTime = SystemClock.elapsedRealtime()
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var isFirstReading = true

    private val idleCheckRunnable = object : Runnable {
        override fun run() {
            checkIdleState()
            handler.postDelayed(this, IDLE_CHECK_INTERVAL_MS.toLong())
        }
    }

    fun start() {
        if (::handlerThread.isInitialized && handlerThread.isAlive) {
            Log.d(TAG, "IdleStateDetector already running")
            return
        }

        handlerThread = HandlerThread("IdleStateDetectorThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        // Register accelerometer with low power consumption rate
        sensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI, // 60ms intervals (~16Hz)
            handler
        )

        // Start periodic idle checks
        handler.post(idleCheckRunnable)

        lastMotionTime = SystemClock.elapsedRealtime()
        _isIdle.value = false
        isFirstReading = true

        Log.d(TAG, "IdleStateDetector started")
    }

    fun stop() {
        if (::handlerThread.isInitialized) {
            sensorManager.unregisterListener(this)
            handler.removeCallbacks(idleCheckRunnable)
            handlerThread.quitSafely()
            Log.d(TAG, "IdleStateDetector stopped")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (isFirstReading) {
            lastX = x
            lastY = y
            lastZ = z
            isFirstReading = false
            return
        }

        // Calculate change in acceleration (delta)
        // This effectively removes gravity since we're looking at changes
        val deltaX = abs(x - lastX)
        val deltaY = abs(y - lastY)
        val deltaZ = abs(z - lastZ)

        // Calculate total motion magnitude
        val motionMagnitude = sqrt(
            (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).toDouble()
        ).toFloat()

        // Update last values
        lastX = x
        lastY = y
        lastZ = z

        // Check if motion exceeds threshold
        if (motionMagnitude > MOTION_THRESHOLD) {
            lastMotionTime = SystemClock.elapsedRealtime()

            // If we were idle, we're not anymore
            if (_isIdle.value) {
                _isIdle.value = false
                Log.d(TAG, "Motion detected, exiting idle state (magnitude: $motionMagnitude)")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for idle detection
    }

    private fun checkIdleState() {
        val timeSinceLastMotion = SystemClock.elapsedRealtime() - lastMotionTime
        val shouldBeIdle = timeSinceLastMotion >= IDLE_TIMEOUT_MS

        if (shouldBeIdle && !_isIdle.value) {
            _isIdle.value = true
            Log.d(TAG, "Device idle detected after ${timeSinceLastMotion / 1000}s of no motion")
        }
    }
}