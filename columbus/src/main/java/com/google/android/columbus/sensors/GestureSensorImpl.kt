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
import com.kieronquinn.app.shared.taprt.BaseTapRT

/**
 * Implementation of the Gesture Sensor that manages high-speed sensor polling
 * for gesture detection while implementing aggressive battery saving strategies.
 */
open class GestureSensorImpl(
    private val context: Context
): GestureSensor() {

    // --- CONFIGURATION ---
    private val DEEP_SLEEP_TIMEOUT_MS = 60000L

    // 0 = Fastest. Essential for accurate velocity calculation in TensorFlow.
    private val TARGET_SAMPLING_PERIOD = 0

    // 100000 = No Batching. Essential for real-time responsiveness.
    private val MAX_REPORT_LATENCY = 100000

    // --- SENSORS & MANAGERS ---
    private val handler = Handler(Looper.getMainLooper())
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val significantMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    // --- STATE MANAGEMENT ---
    // 2.5ms (400Hz) - Matches the TF Model's expectation
    protected val samplingIntervalNs = 2500000L

    protected val isRunningInLowSamplingRate = false

    open val tap: BaseTapRT = TapRT(160000000L)
    open val sensorEventListener = GestureSensorEventListener()

    private var isListening = false
    private var shouldBeListening = false
    private var isDeepSleeping = false
    private var activeMotionListener: TriggerEventListener? = null

    // --- NEW: SCREEN STATE RECEIVER ---
    // Acts as a "Pre-emptive" wake up. If the screen turns on, we assume usage
    // and wake the sensors immediately, reducing the "dead zone" latency.
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                // Force wake-up when screen turns on
                exitDeepSleep()
            }
        }
    }

    open inner class GestureSensorEventListener: SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(event: SensorEvent?) {
            if(event == null) return

            resetDeepSleepTimer()

            val sensorType = event.sensor.type
            tap.updateData(
                sensorType,
                event.values[0],
                event.values[1],
                event.values[2],
                event.timestamp,
                samplingIntervalNs,
                isRunningInLowSamplingRate
            )

            val timing = tap.checkDoubleTapTiming(event.timestamp)
            when(timing){
                1 -> handler.post { reportGestureDetected(2, DetectionProperties(true)) }
                2 -> handler.post { reportGestureDetected(1, DetectionProperties(false)) }
            }
        }

        fun setListening(listening: Boolean, samplingPeriod: Int) {
            shouldBeListening = listening
            if (listening) {
                // Register Screen Receiver
                registerScreenReceiver()

                // Check Proximity (Pocket Mode)
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

    private val deepSleepRunnable = Runnable {
        if (shouldBeListening && significantMotionSensor != null) {
            isDeepSleeping = true
            unregisterHighPowerSensors()

            // Wait for physical motion
            activeMotionListener = sensorManager.requestSignificantMotionTrigger(significantMotionSensor) {
                exitDeepSleep()
            }
        }
    }

    /**
     * Wakes the app from Deep Sleep state.
     * Can be called by Motion Sensor OR Screen On event.
     */
    private fun exitDeepSleep() {
        isDeepSleeping = false
        if (shouldBeListening) {
            // Cancel any pending motion triggers since we are already awake
            sensorManager.cancelTriggerSensorSafe(activeMotionListener)
            activeMotionListener = null

            registerHighPowerSensors()
            resetDeepSleepTimer()
        }
    }

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
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            context.registerReceiver(screenStateReceiver, filter)
        } catch (e: Exception) {
            // Already registered or permission issue
        }
    }

    private fun unregisterScreenReceiver() {
        try {
            context.unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {}
    }

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
fun SensorManager.cancelTriggerSensorSafe(listener: TriggerEventListener?) {
    listener?.let { this.cancelTriggerSensor(it, null) }
}

fun SensorManager.requestSignificantMotionTrigger(sensor: Sensor?, onMotionDetected: () -> Unit): TriggerEventListener? {
    if (sensor == null) return null
    val listener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) { onMotionDetected() }
    }
    this.requestTriggerSensor(listener, sensor)
    return listener
}