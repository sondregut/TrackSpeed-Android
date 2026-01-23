package com.trackspeed.android.detection.experimental

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * IMU-based shake detector for automatic scene recovery.
 *
 * Uses gyroscope (primary) and accelerometer (secondary) to detect
 * when the phone is being moved or repositioned. This triggers
 * automatic background model rebuild.
 *
 * Design principles:
 * - Low latency shake detection (~16ms at 60Hz sensors)
 * - Hysteresis to avoid flutter between states
 * - StateFlow for reactive UI updates
 */
@Singleton
class IMUShakeDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {

    companion object {
        private const val TAG = "IMUShakeDetector"
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC STATE
    // ═══════════════════════════════════════════════════════════════

    private val _isShaking = MutableStateFlow(false)
    val isShaking: StateFlow<Boolean> = _isShaking.asStateFlow()

    private val _gyroMagnitude = MutableStateFlow(0f)
    val gyroMagnitude: StateFlow<Float> = _gyroMagnitude.asStateFlow()

    private val _accelMagnitude = MutableStateFlow(0f)
    val accelMagnitude: StateFlow<Float> = _accelMagnitude.asStateFlow()

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL STATE
    // ═══════════════════════════════════════════════════════════════

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null

    private var stableReadingCount = 0
    private var isRunning = false

    // Callbacks
    private var onShake: (() -> Unit)? = null
    private var onStable: (() -> Unit)? = null

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start monitoring IMU sensors.
     *
     * @param onShake Called when shake is detected (transition to moving)
     * @param onStable Called when phone becomes stable (transition to stable)
     */
    fun start(onShake: () -> Unit, onStable: () -> Unit) {
        if (isRunning) return

        this.onShake = onShake
        this.onStable = onStable

        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Register at GAME rate (~60Hz) for responsive detection
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        isRunning = true
        Log.i(TAG, "IMU shake detector started")
    }

    /**
     * Stop monitoring IMU sensors.
     */
    fun stop() {
        if (!isRunning) return

        sensorManager.unregisterListener(this)
        isRunning = false

        onShake = null
        onStable = null

        Log.i(TAG, "IMU shake detector stopped")
    }

    /**
     * Reset to initial state (assumes phone is moving).
     */
    fun reset() {
        stableReadingCount = 0
        _isShaking.value = true
        _gyroMagnitude.value = 0f
        _accelMagnitude.value = 0f
    }

    // ═══════════════════════════════════════════════════════════════
    // SENSOR CALLBACKS
    // ═══════════════════════════════════════════════════════════════

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> processGyroscope(event)
            Sensor.TYPE_ACCELEROMETER -> processAccelerometer(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    // ═══════════════════════════════════════════════════════════════
    // PROCESSING
    // ═══════════════════════════════════════════════════════════════

    private fun processGyroscope(event: SensorEvent) {
        // Magnitude of rotation rate (rad/s)
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)

        _gyroMagnitude.value = magnitude

        // Primary shake detection via gyroscope
        val isMoving = magnitude > ExperimentalConfig.GYRO_SHAKE_THRESHOLD

        updateShakeState(isMoving)
    }

    private fun processAccelerometer(event: SensorEvent) {
        // Remove gravity (approximate) and compute magnitude
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Raw magnitude includes gravity (~9.8 m/s²)
        val rawMagnitude = sqrt(x * x + y * y + z * z)

        // Deviation from gravity indicates movement
        val deviation = kotlin.math.abs(rawMagnitude - SensorManager.GRAVITY_EARTH)
        _accelMagnitude.value = deviation

        // Secondary shake detection via accelerometer
        // Only trigger if gyro already indicates movement (avoid false positives)
        if (deviation > ExperimentalConfig.ACCEL_SHAKE_THRESHOLD && _isShaking.value) {
            // Accelerometer confirms shake, reset stable counter
            stableReadingCount = 0
        }
    }

    private fun updateShakeState(isCurrentlyMoving: Boolean) {
        val wasShaking = _isShaking.value

        if (isCurrentlyMoving) {
            // Movement detected - immediately go to shaking
            stableReadingCount = 0
            if (!wasShaking) {
                _isShaking.value = true
                Log.d(TAG, "Shake detected! gyro=${_gyroMagnitude.value}")
                onShake?.invoke()
            }
        } else {
            // No movement - count stable readings
            stableReadingCount++

            if (stableReadingCount >= ExperimentalConfig.IMU_STABLE_READINGS) {
                if (wasShaking) {
                    _isShaking.value = false
                    Log.d(TAG, "Phone stabilized after $stableReadingCount stable readings")
                    onStable?.invoke()
                }
            }
        }
    }
}
