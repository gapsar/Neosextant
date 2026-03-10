package com.example.basic_neosextant

import kotlin.math.cos
import kotlin.math.sin

/**
 * The Sensor Pipeline transforms Raw Accelerometer Data into a "True Vertical" vector
 * in the Camera's Reference Frame.
 *
 * Steps:
 * 1. Raw Accelerometer (Sensor Frame) -> Apply Sphere Fit -> Calibrated Gravity (Sensor Frame)
 * 2. Calibrated Gravity (Sensor Frame) -> Apply Legacy Pitch Offset -> True Vertical (Camera Frame)
 *
 * Note: We assume the Sensor Frame is aligned with the Phone Body Frame.
 * If Camera Frame differs from Phone Body (e.g. landscape vs portrait), that rotation happens here too.
 * For this app (standard back camera), we typically assume alignment or handle 90-deg rotations.
 * The legacy app seems to handle this in `MainActivity` pitch calculation.
 * Here we specifically handle the *Calibration* parts.
 */
class SensorPipeline(private val calibrator: SensorCalibrator) {

    /**
     * Processes the raw accelerometer vector to find the True Vertical.
     *
     * @param rawAccel The raw [x, y, z] from Sensor.TYPE_ACCELEROMETER.
     * @param legacyPitchOffsetDeg The offset from the "Horizon Calibration" (in degrees).
     *                             Positive offset usually means "True Horizon is Above Measured".
     *                             So we rotate the gravity vector *down* (or camera *up*)?
     *                             Logic: True = Measured + Offset.
     *                             We want the vector representing True Vertical.
     *                             If the phone reads a pitch of 0 (vertical), but offset is +5,
     *                             then true vertical is actually tilted 5 degrees relative to the phone.
     *
     * @return A normalized Vec3 representing the direction of Gravity (Down) in the Camera Frame.
     */
    fun processGravity(rawAccel: SensorCalibrator.Vec3, legacyPitchOffsetDeg: Double): SensorCalibrator.Vec3 {
        // Step 1: Sphere Fit (Hard/Soft Iron for Accel)
        val calibratedG = calibrator.applyCalibration(rawAccel)

        // Step 2: Normalize to get a clean unit vector
        val mag = calibratedG.magnitude()
        val unitG = if (mag > 0) SensorCalibrator.Vec3(calibratedG.x / mag, calibratedG.y / mag, calibratedG.z / mag) else SensorCalibrator.Vec3(0f, 0f, 0f)

        // Step 3: Apply Legacy Offset.
        // The offset is a scalar "Pitch correction".
        // We need to rotate this vector around the X-axis (Pitch axis).
        //
        // Android Sensor Coordinate System (typically):
        // X: Horizontal (Right)
        // Y: Vertical (Up)
        // Z: Perpendicular to screen (Out)
        //
        // However, usually for Camera apps in Landscape/Portrait:
        // We need to be careful about the axis.
        // Assuming standard Android coordinate system for the sensor.

        // Rotation around X-axis:
        // y' = y*cos(theta) - z*sin(theta)
        // z' = y*sin(theta) + z*cos(theta)

        val thetaRad = Math.toRadians(legacyPitchOffsetDeg)
        val cos = cos(thetaRad).toFloat()
        val sin = sin(thetaRad).toFloat()

        // The legacy offset "Corrects" the measured pitch.
        // If Sensor says 45deg, and Offset is +5deg, True is 50deg.
        // This implies the standard "Down" vector is rotated by -5deg relative to the sensor?
        // Or the sensor is rotated?
        // Let's assume we rotate the *vector* by the offset.

        val rotatedY = unitG.y * cos - unitG.z * sin
        val rotatedZ = unitG.y * sin + unitG.z * cos

        return SensorCalibrator.Vec3(unitG.x, rotatedY, rotatedZ)
    }
}
