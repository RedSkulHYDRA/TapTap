package com.google.android.columbus.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.kieronquinn.app.shared.taprt.BaseTapRT

/**
 * Implementation of the Gesture Sensor that manages high-speed sensor polling
 * for gesture detection while implementing aggressive battery saving strategies.
 *
 * This class handles the low-level sensor lifecycle, using proximity and motion
 * sensors to gate high-power accelerometer and gyroscope usage. It implements
 * a four-tier power management system:
 *
 * 1. High-Power Mode: Both sensors at ~400Hz (SENSOR_DELAY_FASTEST)
 * 2. Low-Power Mode: Accelerometer only at 1Hz
 * 3. Deep-Sleep Mode: All sensors OFF (after 3 hours of no interaction)
 * 4. Proximity-Based Gating: Disables sensors when device is in pocket
 */
open class GestureSensorImpl(
    private val context: Context
): GestureSensor() {

    companion object {
        private const val TAG = "GestureSensorImpl"
    }

    // --- CONFIGURATION ---

    /**
     * Duration of inactivity (in ms) before the high-power sensors are suspended
     * to enter Low-Power Mode (accelerometer only at 1Hz).
     */
    private val LOW_POWER_TIMEOUT_MS = 30000L // 30 Seconds

    /**
     * Duration in low-power mode before entering deep sleep (all sensors OFF).
     * After 3 hours of no user interaction, sensors completely shut down.
     */
    private val DEEP_SLEEP_TIMEOUT_MS = 10800000L  // 3 hours

    /**
     * 0 = SENSOR_DELAY_FASTEST. Essential for the TensorFlow model to receive
     * high-density data points (~400Hz) for accurate peak detection.
     */
    private val TARGET_SAMPLING_PERIOD = 0

    /**
     * 1Hz for low-power accelerometer monitoring.
     * Sufficient for detecting device motion while using less battery than
     * running both accelerometer and gyroscope at high speed.
     */
    private val LOW_POWER_SAMPLING_DELAY = 1000000

    /**
     * Batching latency in microseconds. Set to a non-zero value to allow the
     * Hardware Sensor Hub to buffer events, reducing CPU wake-ups.
     */
    private val MAX_REPORT_LATENCY = 20000

    /**
     * Motion threshold (in m/s²) for exiting low-power mode.
     * When accelerometer detects movement above this threshold, the system
     * returns to high-power mode for gesture detection.
     */
    private val MOTION_THRESHOLD = 1.0f

    // --- SENSORS & MANAGERS ---

    /**
     * Background thread for sensor processing to avoid blocking the main thread.
     * All sensor callbacks are processed here, with gesture callbacks posted to main thread.
     */
    private val sensorThread = HandlerThread("GestureSensorThread", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
        start()
        Log.d(TAG, "Sensor background thread started with high priority")
    }
    private val sensorHandler = Handler(sensorThread.looper)

    /**
     * Main thread handler for callbacks and UI updates.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    // --- STATE MANAGEMENT ---

    /**
     * Sampling interval expected by the TapRT model (2.5ms / 400Hz).
     * Modification will negatively impact gesture recognition accuracy.
     */
    protected val samplingIntervalNs = 2500000L

    protected var isRunningInLowSamplingRate = false

    /**
     * The Tap Runtime (TF Model) initialized with a 160ms window.
     */
    open val tap: BaseTapRT = TapRT(160000000L)
    open val sensorEventListener = GestureSensorEventListener()

    private var isListening = false
    private var shouldBeListening = false
    private var isInLowPowerMode = false

    // Default to TRUE to ensure Strict Gating works.
    // We assume it IS covered until the proximity sensor tells us otherwise.
    // This forces the 'Uncovered' event to trigger the high-power registration.
    private var isProximityCovered = true

    /**
     * Tracks if the device is in deep sleep mode (all sensors OFF)
     */
    private var isInDeepSleep = false

    /**
     * Timestamp of last user interaction, used to determine deep sleep timeout
     */
    private var lastUserInteractionTime = System.currentTimeMillis()

    private val lowPowerMotionListener = LowPowerMotionListener()

    /**
     * Broadcast receiver for monitoring user activity.
     * Wakes from deep sleep only when user unlocks the device.
     */
    private val userActivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                Log.d(TAG, "ACTION_USER_PRESENT - User unlocked device")
                onUserInteraction()

                if (isInDeepSleep && shouldBeListening) {
                    Log.i(TAG, "Waking from deep sleep due to user unlock")
                    exitDeepSleep()
                }
            }
        }
    }

    init {
        Log.d(TAG, "GestureSensorImpl initialized")

        // IMMEDIATE PRIORITY BOOST: Ensures the process gets CPU time right away
        // when the service starts/restarts (e.g., after settings changes).
        mainHandler.postAtFrontOfQueue {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                Log.i(TAG, "Process priority boosted to URGENT_DISPLAY at initialization")

                val startTime = System.currentTimeMillis()

                // Force a few rapid main thread operations to "prove" we're active
                repeat(3) { iteration ->
                    mainHandler.post {
                        Log.v(TAG, "Process activation pulse $iteration")
                    }
                }

                // Schedule a sensor initialization check after a brief delay
                mainHandler.postDelayed({
                    Log.i(TAG, "Initialization complete after ${System.currentTimeMillis() - startTime}ms")
                }, 100L)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to boost process priority", e)
            }
        }

        // Register broadcast receiver for user presence events
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(userActivityReceiver, filter)

        Log.d(TAG, "Accelerometer available: ${accelerometer != null}")
        Log.d(TAG, "Gyroscope available: ${gyroscope != null}")
        Log.d(TAG, "Proximity sensor available: ${proximitySensor != null}")
        Log.d(TAG, "Low power timeout: ${LOW_POWER_TIMEOUT_MS}ms")
        Log.d(TAG, "Deep sleep timeout: ${DEEP_SLEEP_TIMEOUT_MS}ms (${DEEP_SLEEP_TIMEOUT_MS / 60000} minutes)")
        Log.d(TAG, "Motion threshold: $MOTION_THRESHOLD m/s²")
    }

    /**
     * Called whenever user interaction is detected.
     * Resets the deep sleep timeout counter.
     */
    private fun onUserInteraction() {
        lastUserInteractionTime = System.currentTimeMillis()
        Log.v(TAG, "User interaction detected - deep sleep timer reset")

        if (isInLowPowerMode || isInDeepSleep) {
            mainHandler.removeCallbacks(deepSleepRunnable)
            if (isInLowPowerMode && !isInDeepSleep) {
                mainHandler.postDelayed(deepSleepRunnable, DEEP_SLEEP_TIMEOUT_MS)
                Log.v(TAG, "Deep sleep scheduled in ${DEEP_SLEEP_TIMEOUT_MS / 60000} minutes")
            }
        }
    }

    open inner class GestureSensorEventListener: SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.v(TAG, "Sensor accuracy changed: ${sensor?.name}, accuracy: $accuracy")
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if(event == null) return

            // Interaction detected; reset the countdown to low power mode
            resetLowPowerTimer()
            onUserInteraction()

            val sensorType = event.sensor.type

            // Feed raw data into the Gesture Recognition engine
            tap.updateData(
                sensorType,
                event.values[0],
                event.values[1],
                event.values[2],
                event.timestamp,
                samplingIntervalNs,
                isRunningInLowSamplingRate
            )

            // Detect double/triple tap timing windows
            val timing = tap.checkDoubleTapTiming(event.timestamp)
            when(timing){
                1 -> {
                    Log.i(TAG, "Triple tap detected (high-power mode)")
                    // Post gesture callback to main thread
                    mainHandler.post { reportGestureDetected(2, DetectionProperties(true)) }
                    onUserInteraction()
                }
                2 -> {
                    Log.i(TAG, "Double tap detected (high-power mode)")
                    // Post gesture callback to main thread
                    mainHandler.post { reportGestureDetected(1, DetectionProperties(false)) }
                    onUserInteraction()
                }
            }
        }

        /**
         * Sets the desired listening state.
         * Logic-aware: manages pocket detection and power-saving timers.
         */
        fun setListening(listening: Boolean, samplingPeriod: Int) {
            Log.d(TAG, "setListening called: listening=$listening, samplingPeriod=$samplingPeriod")
            shouldBeListening = listening
            if (listening) {
                onUserInteraction()

                sensorHandler.post {
                    Log.d(TAG, "Sensor thread: Starting sensor registration")

                    if (proximitySensor != null) {
                        Log.d(TAG, "Registering proximity sensor listener")
                        // STRICT GATING: We ONLY register proximity.
                        // We rely on the proximityListener to callback and start High Power
                        // if the sensor reports "Uncovered".
                        sensorManager.registerListener(proximityListener, proximitySensor, LOW_POWER_SAMPLING_DELAY, sensorHandler)
                    } else {
                        // No proximity sensor, must go straight to high power
                        Log.d(TAG, "No proximity sensor, directly registering high-power sensors")
                        registerHighPowerSensors()
                    }

                    Log.i(TAG, "Sensor registration completed on sensor thread")
                }
                resetLowPowerTimer()
                setListeningState(true)
            } else {
                Log.d(TAG, "Stopping all sensors")
                stopAllSensors()
                setListeningState(false)
            }
        }
    }

    // --- BATTERY LOGIC ---

    /**
     * Suspends high-power sensors when the proximity sensor is covered (e.g., in pocket).
     * When covered, switches to low-power mode. When uncovered, returns to high-power mode.
     */
    private val proximityListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(event: SensorEvent?) {
            val range = event?.sensor?.maximumRange ?: 1f
            val value = event?.values?.get(0) ?: 1f
            val isCovered = value < range

            Log.v(TAG, "Proximity sensor: value=$value, range=$range, covered=$isCovered")

            val wasProximityCovered = isProximityCovered
            isProximityCovered = isCovered

            if (isCovered) {
                Log.d(TAG, "Device in pocket - switching to low-power mode")
                if (!isInLowPowerMode) {
                    enterLowPowerMode()
                }
            } else if (shouldBeListening && wasProximityCovered) {
                // This block handles the "Uncovered" event.
                // Because we initialized isProximityCovered = true, this block WILL fire
                // on the very first "Uncovered" event after startup, enabling the sensors.
                Log.d(TAG, "Device out of pocket - returning to high-power mode")
                if (isInLowPowerMode) {
                    exitLowPowerMode()
                } else {
                    registerHighPowerSensors()
                }
            }
        }
    }

    /**
     * Low-power motion listener - ACCELEROMETER ONLY for better battery life.
     * Gyroscope is disabled in low-power mode as accelerometer alone is sufficient
     * for detecting device movement (picking up, walking, tilting, etc.).
     *
     * Runs at 1Hz vs ~400Hz in high-power mode, providing
     * battery savings while maintaining motion detection capability.
     */
    private inner class LowPowerMotionListener : SensorEventListener {
        private var lastAccelValues = FloatArray(3) { 0f }
        private var isFirstReading = true

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || !isInLowPowerMode) return

            // Only process accelerometer in low-power mode
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

            if (isFirstReading) {
                lastAccelValues = event.values.clone()
                isFirstReading = false
                Log.v(TAG, "First accelerometer reading in low-power mode")
                return
            }

            // Calculate magnitude of change
            val deltaX = event.values[0] - lastAccelValues[0]
            val deltaY = event.values[1] - lastAccelValues[1]
            val deltaZ = event.values[2] - lastAccelValues[2]
            val magnitude = Math.sqrt((deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).toDouble()).toFloat()

            lastAccelValues = event.values.clone()

            Log.v(TAG, "Low-power accel motion: magnitude=$magnitude m/s²")

            // Only exit low-power if NOT in pocket
            if (magnitude > MOTION_THRESHOLD && !isProximityCovered) {
                Log.i(TAG, "Motion threshold exceeded on accelerometer: $magnitude > $MOTION_THRESHOLD m/s²")
                onUserInteraction()
                exitLowPowerMode()
            }
        }

        fun reset() {
            isFirstReading = true
            lastAccelValues = FloatArray(3) { 0f }
            Log.v(TAG, "Low-power motion listener reset")
        }
    }

    /**
     * Runnable that triggers low-power mode after inactivity timeout.
     */
    private val lowPowerRunnable = Runnable {
        Log.i(TAG, "Inactivity timeout reached - entering low-power mode")
        if (shouldBeListening && !isInLowPowerMode && !isInDeepSleep) {
            enterLowPowerMode()
        }
    }

    /**
     * Runnable that triggers deep sleep after extended inactivity (3 hours)
     */
    private val deepSleepRunnable = Runnable {
        val inactiveDuration = System.currentTimeMillis() - lastUserInteractionTime
        Log.i(TAG, "Deep sleep timeout reached - no interaction for ${inactiveDuration / 60000} minutes")

        if (shouldBeListening && isInLowPowerMode && !isInDeepSleep) {
            enterDeepSleep()
        }
    }

    /**
     * Enters low-power mode: switches from dual-sensor 400Hz to single-sensor 1Hz.
     * This provides battery savings while maintaining motion detection capability.
     */
    private fun enterLowPowerMode() {
        if (isInLowPowerMode || isInDeepSleep) {
            Log.v(TAG, "Already in low-power or deep sleep mode, skipping")
            return
        }

        Log.i(TAG, "Entering low-power mode (proximity covered: $isProximityCovered)")
        isInLowPowerMode = true
        isRunningInLowSamplingRate = true
        lowPowerMotionListener.reset()

        unregisterHighPowerSensors()
        registerLowPowerSensors()

        // Schedule deep sleep after 3 hours of low-power mode
        mainHandler.postDelayed(deepSleepRunnable, DEEP_SLEEP_TIMEOUT_MS)
        Log.i(TAG, "Low-power mode active - accelerometer only at 1Hz")
        Log.i(TAG, "Deep sleep scheduled in ${DEEP_SLEEP_TIMEOUT_MS / 60000} minutes")
    }

    /**
     * Exits low-power mode and returns to high-speed dual-sensor polling.
     * Only exits if proximity is not covered to prevent unnecessary battery drain.
     */
    private fun exitLowPowerMode() {
        if (!isInLowPowerMode) {
            Log.v(TAG, "Not in low-power mode, skipping exit")
            return
        }

        // Don't exit if proximity is covered - stay in low-power
        if (isProximityCovered) {
            Log.d(TAG, "Proximity covered - staying in low-power mode")
            return
        }

        Log.i(TAG, "Exiting low-power mode - returning to high-speed polling")
        isInLowPowerMode = false
        isRunningInLowSamplingRate = false

        mainHandler.removeCallbacks(deepSleepRunnable)

        if (shouldBeListening) {
            unregisterLowPowerSensors()
            registerHighPowerSensors()
            resetLowPowerTimer()
            Log.i(TAG, "High-power mode active - both sensors at ~400Hz (SENSOR_DELAY_FASTEST)")
        }
    }

    /**
     * Enters deep sleep mode: all sensors OFF.
     * Triggered after 3 hours of no user interaction.
     */
    private fun enterDeepSleep() {
        if (isInDeepSleep) {
            Log.v(TAG, "Already in deep sleep, skipping")
            return
        }

        val inactiveDuration = System.currentTimeMillis() - lastUserInteractionTime
        Log.i(TAG, "→ DEEP SLEEP MODE (all sensors OFF, ~0% battery)")
        Log.i(TAG, "Inactive for ${inactiveDuration / 60000} minutes - complete sensor shutdown")

        isInDeepSleep = true
        isInLowPowerMode = false
        isRunningInLowSamplingRate = false

        unregisterHighPowerSensors()
        unregisterLowPowerSensors()

        Log.i(TAG, "Deep sleep active - will wake on user unlock")
    }

    /**
     * Exits deep sleep mode and returns sensors to active state.
     * Triggered by ACTION_USER_PRESENT.
     */
    private fun exitDeepSleep() {
        if (!isInDeepSleep) {
            Log.v(TAG, "Not in deep sleep, skipping exit")
            return
        }

        Log.i(TAG, "← Exiting DEEP SLEEP MODE")
        isInDeepSleep = false

        // Reset interaction time
        onUserInteraction()

        if (shouldBeListening) {
            // Waking up implies user is present, so jump to high power mode
            Log.i(TAG, "Returning to high-power mode")
            registerHighPowerSensors()
            resetLowPowerTimer()
        }
    }

    /**
     * Register ONLY accelerometer in low-power mode for battery savings.
     * Gyroscope is disabled as accelerometer alone is sufficient for motion detection.
     * Runs at 1Hz instead of ~400Hz.
     */
    private fun registerLowPowerSensors() {
        unregisterLowPowerSensors()
        if (accelerometer != null) {
            Log.d(TAG, "Registering low-power sensors (ACCELEROMETER ONLY at 1Hz)")
            sensorManager.registerListener(
                lowPowerMotionListener,
                accelerometer,
                LOW_POWER_SAMPLING_DELAY,
                sensorHandler // Use background thread
            )
            Log.d(TAG, "Low-power accelerometer registered successfully")
        } else {
            Log.w(TAG, "Cannot register low-power sensors - accelerometer not available")
        }
    }

    private fun unregisterLowPowerSensors() {
        try {
            sensorManager.unregisterListener(lowPowerMotionListener, accelerometer)
            // Note: Gyroscope not registered in low-power mode
            Log.v(TAG, "Low-power sensors unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering low-power sensors", e)
        }
    }

    /**
     * Standard registration for Accelerometer and Gyroscope using high-speed polling.
     * Both sensors run at ~400Hz (SENSOR_DELAY_FASTEST) for optimal gesture detection.
     */
    private fun registerHighPowerSensors() {
        unregisterHighPowerSensors()
        if (accelerometer != null && gyroscope != null) {
            Log.d(TAG, "Registering high-power sensors (BOTH at ~400Hz)")
            sensorManager.registerListener(
                sensorEventListener,
                accelerometer,
                TARGET_SAMPLING_PERIOD,
                MAX_REPORT_LATENCY,
                sensorHandler // Use background thread
            )
            sensorManager.registerListener(
                sensorEventListener,
                gyroscope,
                TARGET_SAMPLING_PERIOD,
                MAX_REPORT_LATENCY,
                sensorHandler // Use background thread
            )
            Log.d(TAG, "High-power sensors registered successfully")
        } else {
            Log.w(TAG, "Cannot register high-power sensors - accelerometer or gyroscope not available")
        }
    }

    private fun unregisterHighPowerSensors() {
        try {
            sensorManager.unregisterListener(sensorEventListener, accelerometer)
            sensorManager.unregisterListener(sensorEventListener, gyroscope)
            Log.v(TAG, "High-power sensors unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering high-power sensors", e)
        }
    }

    /**
     * Stops all active sensor listeners and removes pending sleep callbacks.
     * Background thread remains alive for reuse to prevent thread restart issues.
     */
    private fun stopAllSensors() {
        Log.d(TAG, "Stopping all sensors")
        unregisterHighPowerSensors()
        unregisterLowPowerSensors()
        if (proximitySensor != null) {
            sensorManager.unregisterListener(proximityListener)
            Log.d(TAG, "Proximity sensor unregistered")
        }
        mainHandler.removeCallbacks(lowPowerRunnable)
        mainHandler.removeCallbacks(deepSleepRunnable)

        isInLowPowerMode = false
        isInDeepSleep = false
        isRunningInLowSamplingRate = false
        isProximityCovered = true // Reset to true to ensure gating logic works on next start
        Log.i(TAG, "All sensors stopped")

        // Thread remains alive for future use - do NOT call quitSafely()
        // The background thread has minimal overhead when idle and can be reused
        // preventing thread restart crashes when user re-enables the feature
    }

    private fun resetLowPowerTimer() {
        mainHandler.removeCallbacks(lowPowerRunnable)
        mainHandler.removeCallbacks(deepSleepRunnable)
        mainHandler.postDelayed(lowPowerRunnable, LOW_POWER_TIMEOUT_MS)
        Log.v(TAG, "Low-power timer reset (${LOW_POWER_TIMEOUT_MS}ms)")
    }

    private fun setListeningState(listening: Boolean) {
        this.isListening = listening
        Log.d(TAG, "Listening state set to: $listening")
    }

    // --- OVERRIDES ---

    override fun isListening(): Boolean = isListening

    override fun startListening(heuristicMode: Boolean) {
        Log.i(TAG, "startListening called with heuristicMode=$heuristicMode")
        sensorEventListener.setListening(true, TARGET_SAMPLING_PERIOD)

        if(heuristicMode) {
            Log.d(TAG, "Configuring TapRT for heuristic mode")
            (tap as? TapRT)?.run {
                getLowpassKey().setPara(0.2f)
                getHighpassKey().setPara(0.2f)
                getPositivePeakDetector().setMinNoiseTolerate(0.05f)
                getPositivePeakDetector().setWindowSize(0x40)
                reset(false)
            }
        } else {
            Log.d(TAG, "Configuring TapRT for standard mode")
            (tap as? TapRT)?.run {
                getLowpassKey().setPara(1f)
                getHighpassKey().setPara(0.3f)
                getPositivePeakDetector().setMinNoiseTolerate(0.02f)
                getPositivePeakDetector().setWindowSize(8)
                getNegativePeakDetection().setMinNoiseTolerate(0.02f)
                getNegativePeakDetection().setWindowSize(8)
                reset(true)
            }
        }
        Log.i(TAG, "Gesture sensor listening started")
    }

    override fun stopListening() {
        Log.i(TAG, "stopListening called")
        sensorEventListener.setListening(false, 0)
        Log.i(TAG, "Gesture sensor listening stopped")
    }

    /**
     * Cleanup method to be called when the sensor is no longer needed (e.g., on component destruction).
     * This properly terminates the background thread to prevent memory leaks.
     *
     * CRITICAL: Must be called in onDestroy() or equivalent lifecycle method!
     * Failure to call this will result in thread leaks and potential memory issues.
     */
    fun cleanup() {
        Log.i(TAG, "cleanup() called - stopping all sensors and terminating background thread")

        // Unregister broadcast receiver
        try {
            context.unregisterReceiver(userActivityReceiver)
            Log.d(TAG, "User activity receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering user activity receiver", e)
        }

        stopAllSensors()
        sensorThread.quitSafely()
        try {
            sensorThread.join(1000) // Wait up to 1 second for thread to terminate
            Log.d(TAG, "Sensor background thread terminated successfully")
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error waiting for sensor thread to terminate", e)
            Thread.currentThread().interrupt() // Restore interrupt status
        }
    }
}