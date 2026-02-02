package com.google.android.columbus.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.kieronquinn.app.shared.taprt.BaseTapRT

/**
 * Implementation of the Gesture Sensor that manages high-speed sensor polling
 * for gesture detection while implementing aggressive battery saving strategies.
 *
 * This class handles the low-level sensor lifecycle, using proximity and motion
 * sensors to gate high-power accelerometer and gyroscope usage.
 */
open class GestureSensorImpl(
    private val context: Context
): GestureSensor() {

    // --- CONFIGURATION ---

    /**
     * Duration of inactivity (in ms) before the high-power sensors are suspended
     * to enter a Deep Sleep state.
     */
    private val DEEP_SLEEP_TIMEOUT_MS = 60000L

    /**
     * 0 = SENSOR_DELAY_FASTEST. Essential for the TensorFlow model to receive
     * high-density data points (~400Hz) for accurate peak detection.
     */
    private val TARGET_SAMPLING_PERIOD = 0

    /**
     * Batching latency in microseconds. Set to a non-zero value to allow the
     * Hardware Sensor Hub to buffer events, reducing CPU wake-ups.
     */
    private val MAX_REPORT_LATENCY = 10000

    // --- SENSORS & MANAGERS ---
    private val handler = Handler(Looper.getMainLooper())
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val significantMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    // --- STATE MANAGEMENT ---

    /**
     * Sampling interval expected by the TapRT model (2.5ms / 400Hz).
     * Modification will negatively impact gesture recognition accuracy.
     */
    protected val samplingIntervalNs = 2500000L

    protected val isRunningInLowSamplingRate = false

    /**
     * The Tap Runtime (TF Model) initialized with a 160ms window.
     */
    open val tap: BaseTapRT = TapRT(160000000L)
    open val sensorEventListener = GestureSensorEventListener()

    private var isListening = false
    private var shouldBeListening = false
    private var isDeepSleeping = false
    private var activeMotionListener: TriggerEventListener? = null

    /**
     * Pre-emptively wakes the sensors when the screen turns on.
     * This reduces the latency "dead-zone" between picking up a device and
     * the Significant Motion sensor triggering.
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val powerManager = ctx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> {
                    exitDeepSleep()
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (powerManager?.isInteractive == true) {
                        exitDeepSleep()
                    }
                }
            }
        }
    }

    open inner class GestureSensorEventListener: SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(event: SensorEvent?) {
            if(event == null) return

            // Interaction detected; reset the countdown to deep sleep
            resetDeepSleepTimer()

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
                1 -> handler.post { reportGestureDetected(2, DetectionProperties(true)) }
                2 -> handler.post { reportGestureDetected(1, DetectionProperties(false)) }
            }
        }

        /**
         * Sets the desired listening state.
         * Logic-aware: manages pocket detection and power-saving timers.
         */
        fun setListening(listening: Boolean, samplingPeriod: Int) {
            shouldBeListening = listening
            if (listening) {
                registerScreenReceiver()

                // Use Proximity to gate high-power sensors (Pocket Mode)
                if (proximitySensor != null) {
                    sensorManager.registerListener(proximityListener, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
                } else {
                    registerHighPowerSensors()
                }
                resetDeepSleepTimer()
                setListeningState(true)
            } else {
                stopAllSensors()
                setListeningState(false)
            }
        }
    }

    // --- BATTERY LOGIC ---

    /**
     * Suspends high-power sensors when the proximity sensor is covered (e.g., in pocket).
     */
    private val proximityListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(event: SensorEvent?) {
            val range = event?.sensor?.maximumRange ?: 1f
            val value = event?.values?.get(0) ?: 1f
            val isCovered = value < range

            if (isCovered) unregisterHighPowerSensors()
            else if (shouldBeListening && !isDeepSleeping) registerHighPowerSensors()
        }
    }

    /**
     * Puts the app into a ultra-low-power state, unregistering high-power sensors
     * and relying on the hardware Significant Motion trigger to wake up.
     */
    private val deepSleepRunnable = Runnable {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = pm.isInteractive

        if (shouldBeListening && significantMotionSensor != null && !isScreenOn) {
            isDeepSleeping = true
            unregisterHighPowerSensors()

            activeMotionListener = sensorManager.requestSignificantMotionTrigger(significantMotionSensor) {
                exitDeepSleep()
            }
        } else if (isScreenOn) {
            resetDeepSleepTimer()
        }
    }

    /**
     * Wakes the app from Deep Sleep state.
     * Triggered by physical movement (Motion Sensor) or user interaction (Screen On).
     */
    private fun exitDeepSleep() {
        isDeepSleeping = false
        if (shouldBeListening) {
            sensorManager.cancelTriggerSensorSafe(activeMotionListener)
            activeMotionListener = null

            registerHighPowerSensors()
            resetDeepSleepTimer()
        }
    }

    /**
     * Standard registration for Accelerometer and Gyroscope using high-speed polling.
     */
    private fun registerHighPowerSensors() {
        unregisterHighPowerSensors()
        if (accelerometer != null && gyroscope != null) {
            sensorManager.registerListener(sensorEventListener, accelerometer, TARGET_SAMPLING_PERIOD, MAX_REPORT_LATENCY, handler)
            sensorManager.registerListener(sensorEventListener, gyroscope, TARGET_SAMPLING_PERIOD, MAX_REPORT_LATENCY, handler)
        }
    }

    private fun unregisterHighPowerSensors() {
        try {
            sensorManager.unregisterListener(sensorEventListener, accelerometer)
            sensorManager.unregisterListener(sensorEventListener, gyroscope)
        } catch (e: Exception) {}
    }

    private fun registerScreenReceiver() {
        try {
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_USER_PRESENT)
            context.registerReceiver(screenStateReceiver, filter)
        } catch (e: Exception) {}
    }

    private fun unregisterScreenReceiver() {
        try {
            context.unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {}
    }

    /**
     * Stops all active sensor listeners and removes pending sleep callbacks.
     */
    private fun stopAllSensors() {
        unregisterHighPowerSensors()
        unregisterScreenReceiver()
        if (proximitySensor != null) sensorManager.unregisterListener(proximityListener)
        sensorManager.cancelTriggerSensorSafe(activeMotionListener)
        activeMotionListener = null
        handler.removeCallbacks(deepSleepRunnable)
    }

    private fun resetDeepSleepTimer() {
        handler.removeCallbacks(deepSleepRunnable)
        if (significantMotionSensor != null) {
            handler.postDelayed(deepSleepRunnable, DEEP_SLEEP_TIMEOUT_MS)
        }
    }

    private fun setListeningState(listening: Boolean) {
        this.isListening = listening
    }

    // --- OVERRIDES ---
    override fun isListening(): Boolean { return isListening }

    override fun startListening(heuristicMode: Boolean) {
        sensorEventListener.setListening(true, TARGET_SAMPLING_PERIOD)

        if(heuristicMode) {
            (tap as? TapRT)?.run {
                getLowpassKey().setPara(0.2f)
                getHighpassKey().setPara(0.2f)
                getPositivePeakDetector().setMinNoiseTolerate(0.05f)
                getPositivePeakDetector().setWindowSize(0x40)
                reset(false)
            }
        } else {
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
    }

    override fun stopListening() {
        sensorEventListener.setListening(false, 0)
    }
}

// --- EXTENSIONS ---

/**
 * Safely cancels a TriggerEventListener for one-shot sensors.
 */
fun SensorManager.cancelTriggerSensorSafe(listener: TriggerEventListener?) {
    listener?.let { this.cancelTriggerSensor(it, null) }
}

/**
 * Helper to request a Significant Motion trigger with a callback.
 */
fun SensorManager.requestSignificantMotionTrigger(sensor: Sensor?, onMotionDetected: () -> Unit): TriggerEventListener? {
    if (sensor == null) return null
    val listener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) { onMotionDetected() }
    }
    this.requestTriggerSensor(listener, sensor)
    return listener
}