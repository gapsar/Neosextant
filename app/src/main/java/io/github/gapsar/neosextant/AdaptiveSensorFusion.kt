package io.github.gapsar.neosextant

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Kalman-filtered sensor fusion for optimal gravity vector estimation.
 *
 * Combines raw accelerometer (absolute but noisy) with gyroscope (smooth but drifts)
 * using a scalar-covariance Kalman filter that automatically computes the optimal
 * blending weight at every step.
 *
 * Unlike Android's TYPE_GRAVITY (fixed, conservative parameters) or a simple
 * complementary filter (heuristic blending), this filter:
 *
 * 1. Tracks its own uncertainty (covariance P) — no manual tuning needed
 * 2. After motion: P is high from gyro drift → Kalman gain K rises → fast settling
 * 3. During stillness: P converges low → K stays minimal → maximum noise rejection
 * 4. Adapts measurement trust when linear acceleration is detected (adaptive R)
 *
 * The filter uses an isotropic (scalar) covariance approximation — treating all three
 * gravity vector components identically. This is valid because:
 * - MEMS accelerometer noise is roughly isotropic
 * - MEMS gyroscope noise is roughly isotropic
 * - We extract a 1D quantity (pitch) from the 3D vector
 *
 * Typical performance at 50 Hz sampling:
 * - Steady-state noise: ~0.01° (std dev) on pitch
 * - Settling after motion: ~500ms to 95%
 * - Steady-state Kalman gain: ~0.04
 *
 * Pipeline:
 *   Raw Accel → Sphere-Fit Calibration (external) → THIS FILTER → Unit Gravity Vector
 *   Raw Gyro  →                                    ↗ (prediction step)
 */
class AdaptiveSensorFusion {

    // ===== STATE =====

    /** Estimated gravity direction (unit vector in phone sensor frame). */
    @Volatile
    private var estimatedGravity: SensorCalibrator.Vec3? = null

    /**
     * Scalar error covariance (isotropic approximation).
     * Represents the variance of each gravity vector component estimate.
     * Units: (unitless)² — operating on normalized gravity vectors.
     */
    private var p: Float = INITIAL_P

    /** Timestamp of last gyroscope event (nanoseconds, monotonic). */
    private var lastGyroTimestampNs: Long = 0

    /** Current Kalman gain — exposed for diagnostics/UI. */
    @Volatile
    private var currentKalmanGain: Float = 0f

    companion object {

        // ===== PROCESS MODEL (Gyroscope) =====

        /**
         * Gyroscope angular rate noise variance, in (rad/s)².
         *
         * Typical phone MEMS gyroscope noise density: 0.005–0.02 rad/s/√Hz.
         * Using σ_ω ≈ 0.01 rad/s as a representative value.
         *
         * Process noise per step: Q_step = σ²_ω × dt²
         * At 50 Hz (dt=0.02s): Q_step = 1e-4 × 4e-4 = 4e-8
         */
        private const val GYRO_NOISE_VARIANCE = 1e-4f // (0.01 rad/s)²

        // ===== MEASUREMENT MODEL (Accelerometer) =====

        /**
         * Base accelerometer measurement noise variance (normalized gravity units)².
         *
         * Typical phone MEMS accel noise: ~0.005 g per axis per sample.
         * For the unit gravity vector: σ ≈ 0.005.
         */
        private const val R_BASE = 2.5e-5f // (0.005)²

        /**
         * Gain for inflating R when linear acceleration is detected.
         * R_effective = R_BASE  +  R_MOTION_GAIN × excess_deviation²
         *
         * When the accelerometer magnitude deviates from ~1g, the phone is
         * experiencing linear acceleration (hand motion, walking, etc.) and
         * the accel reading is NOT purely gravity. We increase R (distrust accel)
         * to let the gyro prediction ride through until motion stops.
         */
        private const val R_MOTION_GAIN = 0.5f

        /**
         * Fractional deviation from nominal gravity magnitude below which
         * no R inflation is applied. Absorbs small calibration offsets
         * and normal measurement noise.
         * 0.05 = 5% of g ≈ 0.49 m/s²
         */
        private const val ACCEL_DEVIATION_TOLERANCE = 0.05f

        /**
         * Nominal gravity magnitude (m/s²).
         * Used only for the adaptive-R motion detection, not for the filter itself.
         * After sphere-fit calibration, the calibrated magnitude should be close to this.
         */
        private const val NOMINAL_G = 9.81f

        // ===== COVARIANCE BOUNDS =====

        /**
         * Initial covariance: start with high uncertainty.
         * The filter will converge from any initial state within ~10 samples.
         */
        private const val INITIAL_P = 1.0f

        /**
         * Minimum covariance to prevent numerical collapse.
         * Even a "perfectly known" state has some irreducible uncertainty
         * from quantization and model mismatch.
         */
        private const val MIN_P = 1e-9f

        /**
         * Maximum covariance to prevent runaway during extended gyro-only prediction.
         * Caps at roughly "we know nothing about this component".
         */
        private const val MAX_P = 1.0f
    }

    // ===== PREDICTION STEP (Gyroscope) =====

    /**
     * Kalman PREDICTION step: integrate gyroscope angular velocity to propagate
     * the gravity estimate forward in time.
     *
     * This step INCREASES uncertainty (P grows by Q) because the gyroscope
     * has noise that accumulates during integration.
     *
     * @param x Angular velocity around X axis (rad/s)
     * @param y Angular velocity around Y axis (rad/s)
     * @param z Angular velocity around Z axis (rad/s)
     * @param timestampNs SensorEvent.timestamp (nanoseconds, monotonic)
     */
    fun onGyroscopeChanged(x: Float, y: Float, z: Float, timestampNs: Long) {
        val gravity = estimatedGravity
        if (gravity != null && lastGyroTimestampNs != 0L) {
            val dtSec = (timestampNs - lastGyroTimestampNs) * 1e-9f

            // Sanity: skip nonsensical dt (sensor gaps or duplicates)
            if (dtSec in 0.001f..0.1f) {

                // --- State Prediction ---
                // If the phone rotates by ω·dt, gravity in the phone frame rotates by −ω·dt.
                // First-order Rodrigues' rotation:  g' = g − (ω × g) · dt
                //
                // Cross product ω × g:
                val crossX = y * gravity.z - z * gravity.y
                val crossY = z * gravity.x - x * gravity.z
                val crossZ = x * gravity.y - y * gravity.x

                val newX = gravity.x - crossX * dtSec
                val newY = gravity.y - crossY * dtSec
                val newZ = gravity.z - crossZ * dtSec

                // Re-normalize to unit sphere (prevents drift accumulation)
                val mag = sqrt(newX * newX + newY * newY + newZ * newZ)
                if (mag > 0.01f) {
                    estimatedGravity = SensorCalibrator.Vec3(
                        newX / mag, newY / mag, newZ / mag
                    )
                }

                // --- Covariance Prediction ---
                // P_predicted = P + Q,  where Q = σ²_ω × dt²
                // (variance of the angle integration error from gyro noise)
                val q = GYRO_NOISE_VARIANCE * dtSec * dtSec
                p = minOf(MAX_P, p + q)
            }
        }
        lastGyroTimestampNs = timestampNs
    }

    // ===== UPDATE STEP (Accelerometer) =====

    /**
     * Kalman UPDATE step: correct the predicted gravity estimate using the
     * accelerometer measurement.
     *
     * This step DECREASES uncertainty (P shrinks by factor 1−K) because
     * the measurement provides new information about the true gravity direction.
     *
     * The measurement noise R is **adaptively inflated** when the accelerometer
     * magnitude deviates from 1g, indicating linear acceleration (the accel
     * is no longer measuring pure gravity).
     *
     * @param calibratedAccel Accelerometer vector AFTER sphere-fit calibration.
     * @return Optimal gravity direction estimate as a unit vector.
     */
    fun onAccelerometerChanged(calibratedAccel: SensorCalibrator.Vec3): SensorCalibrator.Vec3 {
        val mag = calibratedAccel.magnitude()
        if (mag < 0.1f) {
            return estimatedGravity ?: SensorCalibrator.Vec3(0f, 0f, -1f)
        }

        // Measurement: normalized accelerometer → gravity direction
        val zMeas = SensorCalibrator.Vec3(
            calibratedAccel.x / mag,
            calibratedAccel.y / mag,
            calibratedAccel.z / mag
        )

        val current = estimatedGravity
        if (current == null) {
            // First measurement — initialize state and covariance
            estimatedGravity = zMeas
            p = R_BASE
            currentKalmanGain = 1.0f
            return zMeas
        }

        // --- Adaptive Measurement Noise (R) ---
        // When |accel| ≠ g, the phone is experiencing linear acceleration,
        // and the accelerometer is NOT measuring pure gravity.
        // Inflate R to reduce the filter's trust in this measurement.
        val magDeviation = abs(mag / NOMINAL_G - 1.0f)
        val excessDeviation = maxOf(0f, magDeviation - ACCEL_DEVIATION_TOLERANCE)
        val r = R_BASE + R_MOTION_GAIN * excessDeviation * excessDeviation

        // --- Kalman Gain ---
        // K = P / (P + R)
        // When P >> R: K → 1 (trust measurement — state is uncertain)
        // When P << R: K → 0 (trust prediction — state is well-known)
        val k = p / (p + r)
        currentKalmanGain = k

        // --- State Update ---
        // x_updated = x_predicted + K × (z_measured − x_predicted)
        //           = (1 − K) × x_predicted  +  K × z_measured
        val updatedX = current.x + k * (zMeas.x - current.x)
        val updatedY = current.y + k * (zMeas.y - current.y)
        val updatedZ = current.z + k * (zMeas.z - current.z)

        // Re-project onto unit sphere (the "Extended" in EKF — handles the
        // nonlinear constraint that gravity is a unit vector)
        val updMag = sqrt(updatedX * updatedX + updatedY * updatedY + updatedZ * updatedZ)
        val result = if (updMag > 0.01f) {
            SensorCalibrator.Vec3(updatedX / updMag, updatedY / updMag, updatedZ / updMag)
        } else {
            zMeas
        }

        // --- Covariance Update ---
        // P_updated = (1 − K) × P_predicted
        p = maxOf(MIN_P, (1f - k) * p)

        estimatedGravity = result
        return result
    }

    // ===== NO-GYRO FALLBACK =====

    /**
     * Fallback for devices without a gyroscope: simplified Kalman filter
     * using a random-walk process model (no angular velocity information).
     *
     * Process model: g_{k+1} = g_k + noise  (phone might rotate between samples)
     * This degenerates to an exponential moving average with optimal gain.
     *
     * @param calibratedAccel Calibrated accelerometer vector.
     * @return Smoothed gravity direction as a unit vector.
     */
    fun onAccelerometerChangedNoGyro(calibratedAccel: SensorCalibrator.Vec3): SensorCalibrator.Vec3 {
        val mag = calibratedAccel.magnitude()
        if (mag < 0.1f) {
            return estimatedGravity ?: SensorCalibrator.Vec3(0f, 0f, -1f)
        }

        val zMeas = SensorCalibrator.Vec3(
            calibratedAccel.x / mag,
            calibratedAccel.y / mag,
            calibratedAccel.z / mag
        )

        val current = estimatedGravity
        if (current == null) {
            estimatedGravity = zMeas
            p = R_BASE
            currentKalmanGain = 1.0f
            return zMeas
        }

        // Without gyro, use a random-walk process noise.
        // Q_rw models "the phone might have moved since last sample".
        // Tuned for a hand-held device: ~0.3 deg/step at 50 Hz → σ ≈ 0.005 rad
        val qRandomWalk = 2.5e-5f  // (0.005 rad)²
        p = minOf(MAX_P, p + qRandomWalk)

        // Adaptive R (same as gyro path)
        val magDeviation = abs(mag / NOMINAL_G - 1.0f)
        val excessDeviation = maxOf(0f, magDeviation - ACCEL_DEVIATION_TOLERANCE)
        val r = R_BASE + R_MOTION_GAIN * excessDeviation * excessDeviation

        val k = p / (p + r)
        currentKalmanGain = k

        val updatedX = current.x + k * (zMeas.x - current.x)
        val updatedY = current.y + k * (zMeas.y - current.y)
        val updatedZ = current.z + k * (zMeas.z - current.z)

        val updMag = sqrt(updatedX * updatedX + updatedY * updatedY + updatedZ * updatedZ)
        val result = if (updMag > 0.01f) {
            SensorCalibrator.Vec3(updatedX / updMag, updatedY / updMag, updatedZ / updMag)
        } else {
            zMeas
        }

        p = maxOf(MIN_P, (1f - k) * p)

        estimatedGravity = result
        return result
    }

    // ===== DIAGNOSTICS =====

    /**
     * Returns the current Kalman gain (0..1).
     * - Near 0: filter is confident, output is very stable (steady state)
     * - Near 1: filter is uncertain, rapidly converging (after motion or startup)
     *
     * Can be used to display a "settling" indicator to the user.
     */
    fun getKalmanGain(): Float = currentKalmanGain

    /**
     * Returns the current error covariance.
     * Lower = more confident in the gravity estimate.
     */
    fun getCovariance(): Float = p

    /**
     * Reset filter state. Call on app resume, sensor reconfiguration,
     * or calibration changes.
     */
    fun reset() {
        estimatedGravity = null
        lastGyroTimestampNs = 0
        p = INITIAL_P
        currentKalmanGain = 0f
    }
}
