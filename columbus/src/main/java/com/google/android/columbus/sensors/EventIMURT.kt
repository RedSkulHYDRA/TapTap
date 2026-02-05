package com.google.android.columbus.sensors

import android.util.Log
import java.util.ArrayDeque

open class EventIMURT {

    companion object {
        private const val TAG = "EventIMURT"
    }

    protected var _fv = ArrayList<Float>()
    protected var _gotAcc = false
    protected var _gotGyro = false
    protected var _highpassAcc = Highpass3C()
    protected var _highpassGyro = Highpass3C()
    protected var _lowpassAcc = Lowpass3C()
    protected var _lowpassGyro = Lowpass3C()
    protected var _numberFeature = 0
    protected var _resampleAcc = Resample3C()
    protected var _resampleGyro = Resample3C()
    protected var _sizeFeatureWindow = 0
    protected var _sizeWindowNs = 0L
    protected var _slopeAcc = Slope3C()
    protected var _slopeGyro = Slope3C()
    protected var _syncTime = 0L
    protected var _xsAcc = ArrayDeque<Float>()
    protected var _xsGyro = ArrayDeque<Float>()
    protected var _ysAcc = ArrayDeque<Float>()
    protected var _ysGyro = ArrayDeque<Float>()
    protected var _zsAcc = ArrayDeque<Float>()
    protected var _zsGyro = ArrayDeque<Float>()

    fun processGyro() {
        val currentInterval = _resampleGyro.getInterval()
        if (currentInterval == 0L) {
            Log.v(TAG, "processGyro: currentInterval is 0, skipping (gyro not initialized yet)")
            return
        }

        if (_sizeWindowNs <= 0L) {
            Log.w(TAG, "processGyro: _sizeWindowNs is invalid ($_sizeWindowNs), skipping")
            return
        }

        val resamplePoint = _resampleGyro.results.point
        val scaledInterval = 2500000.0f / currentInterval.toFloat()
        val slopePoint = _slopeGyro.update(resamplePoint, scaledInterval)
        val lowpassPoint = _lowpassGyro.update(slopePoint)
        val highpassPoint = _highpassGyro.update(lowpassPoint)

        _xsGyro.add(highpassPoint.x)
        _ysGyro.add(highpassPoint.y)
        _zsGyro.add(highpassPoint.z)
        val windowSize = (_sizeWindowNs / currentInterval).toInt()
        if (windowSize < 0 || windowSize > 10000) {
            Log.w(TAG, "processGyro: Invalid windowSize calculated: $windowSize (sizeWindowNs=$_sizeWindowNs, currentInterval=$currentInterval)")
            return
        }
        while(_xsGyro.size > windowSize) {
            _xsGyro.removeFirst()
            _ysGyro.removeFirst()
            _zsGyro.removeFirst()
        }
    }

    fun reset() {
        _xsAcc.clear()
        _ysAcc.clear()
        _zsAcc.clear()
        _xsGyro.clear()
        _ysGyro.clear()
        _zsGyro.clear()
        _gotAcc = false
        _gotGyro = false
        _syncTime = 0L
    }

    fun scaleGyroData(data: ArrayList<Float>, scale: Float): ArrayList<Float> {
        if (data.isEmpty()) {
            Log.v(TAG, "scaleGyroData: Empty data array, skipping")
            return data
        }

        val halfSize = data.size / 2
        for(i in halfSize until data.size) {
            data[i] = data[i] * scale
        }
        return data
    }
}