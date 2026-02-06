package com.google.android.columbus.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.kieronquinn.app.shared.taprt.BaseTapRT

open class GestureSensorImpl(
    context: Context
): GestureSensor() {

    companion object {
        private const val TAG = "GestureSensorImpl"
        private const val SENSOR_THREAD_NAME = "GestureSensorThread"
        private const val HEURISTIC_SAMPLING_PERIOD_US = 0 // Fastest possible
        private const val NORMAL_SAMPLING_PERIOD_US = 21000 // ~48Hz
    }

    open inner class GestureSensorEventListener: SensorEventListener {

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // No-op
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return

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

            when (val timing = tap.checkDoubleTapTiming(event.timestamp)) {
                1 -> handler.post {
                    reportGestureDetected(2, DetectionProperties(true))
                }
                2 -> handler.post {
                    reportGestureDetected(1, DetectionProperties(false))
                }
            }
        }

        fun setListening(listening: Boolean, samplingPeriodUs: Int) {
            if (listening) {
                // Check sensor availability
                if (accelerometer == null) {
                    Log.e(TAG, "Accelerometer not available on this device")
                    return
                }
                if (gyroscope == null) {
                    Log.e(TAG, "Gyroscope not available on this device")
                    return
                }

                // Register listeners on background thread
                sensorManager.registerListener(
                    this,
                    accelerometer,
                    samplingPeriodUs,
                    handler
                )
                sensorManager.registerListener(
                    this,
                    gyroscope,
                    samplingPeriodUs,
                    handler
                )
                setListening(true)
                Log.d(TAG, "Started listening (sampling period: ${samplingPeriodUs}µs)")
            } else {
                sensorManager.unregisterListener(this)
                setListening(false)
                Log.d(TAG, "Stopped listening")
            }
        }
    }

    // Background thread for sensor processing
    private val handlerThread = HandlerThread(SENSOR_THREAD_NAME).apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    open val sensorEventListener = GestureSensorEventListener()
    protected val samplingIntervalNs = 2500000L
    protected val isRunningInLowSamplingRate = false

    @Volatile
    private var isListening = false

    open val tap: BaseTapRT = TapRT(160000000L)

    override fun isListening(): Boolean {
        return isListening
    }

    protected fun setListening(listening: Boolean) {
        this.isListening = listening
    }

    override fun startListening(heuristicMode: Boolean) {
        val tapRT = tap as? TapRT

        if (heuristicMode) {
            sensorEventListener.setListening(true, HEURISTIC_SAMPLING_PERIOD_US)
            tapRT?.apply {
                getLowpassKey().setPara(0.2f)
                getHighpassKey().setPara(0.2f)
                getPositivePeakDetector().setMinNoiseTolerate(0.05f)
                getPositivePeakDetector().setWindowSize(0x40)
                reset(false)
                Log.d(TAG, "Started in heuristic mode")
            }
        } else {
            sensorEventListener.setListening(true, NORMAL_SAMPLING_PERIOD_US)
            tapRT?.apply {
                getLowpassKey().setPara(1f)
                getHighpassKey().setPara(0.3f)
                getPositivePeakDetector().setMinNoiseTolerate(0.02f)
                getPositivePeakDetector().setWindowSize(8)
                getNegativePeakDetection().setMinNoiseTolerate(0.02f)
                getNegativePeakDetection().setWindowSize(8)
                reset(true)
                Log.d(TAG, "Started in normal mode")
            }
        }

        if (tapRT == null) {
            Log.w(TAG, "Tap detector is not TapRT - skipping parameter configuration")
        }
    }

    override fun stopListening() {
        sensorEventListener.setListening(false, 0)
    }

    // Clean up background thread when done
    fun cleanup() {
        Log.d(TAG, "Cleaning up GestureSensorImpl")
        stopListening()
        handlerThread.quitSafely()
    }

    // Override finalize as safety net (called by GC if cleanup() is missed)
    protected fun finalize() {
        try {
            cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Error during finalize cleanup", e)
        }
    }
}