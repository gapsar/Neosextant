package io.github.gapsar.neosextant

import kotlin.math.cos
import kotlin.math.sin

/**
 * The Sensor Pipeline transforms Raw Accelerometer Data into a "True Vertical" vector
 * in the Camera's Reference Frame.
 *
 * Applies the 1-Shot star calibration offsets to an already sphere-fit,
 * Kalman-fused gravity direction: a rotation about Z (roll) followed by one
 * about X (pitch). The order and sign must stay identical to the solve in
 * solve_calibration_offsets, which fits the pair.
 *
 * The Sensor Frame is assumed aligned with the Phone Body Frame.
 */
class SensorPipeline(private val calibrator: SensorCalibrator) {

    fun applyOffsets(unitGravity: SensorCalibrator.Vec3, pitchOffsetDeg: Double, rollOffsetDeg: Double): SensorCalibrator.Vec3 {
        var x = unitGravity.x
        var y = unitGravity.y
        var z = unitGravity.z

        // Apply Roll offset (rotation around Z axis)
        if (rollOffsetDeg != 0.0) {
            val rollRad = Math.toRadians(rollOffsetDeg)
            val cosR = cos(rollRad).toFloat()
            val sinR = sin(rollRad).toFloat()
            val newX = x * cosR + y * sinR
            val newY = -x * sinR + y * cosR
            x = newX
            y = newY
        }

        // Apply Pitch offset (rotation around X axis)
        if (pitchOffsetDeg != 0.0) {
            val thetaRad = Math.toRadians(pitchOffsetDeg)
            val cos = cos(thetaRad).toFloat()
            val sin = sin(thetaRad).toFloat()

            val rotatedY = y * cos - z * sin
            val rotatedZ = y * sin + z * cos
            y = rotatedY
            z = rotatedZ
        }

        return SensorCalibrator.Vec3(x, y, z)
    }
}
