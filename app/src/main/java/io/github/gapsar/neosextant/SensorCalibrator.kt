package io.github.gapsar.neosextant

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Handles the "Sphere Fitting" calibration for the accelerometer.
 * Corrects for hard iron (bias) and soft iron (scale) errors.
 *
 * Math based on:
 * "Calibration of MEMS accelerometer by Iterative Least Squares Method"
 * or similar algebraic sphere fitting approaches.
 *
 * Target Equation:
 *   ((Raw - Bias) * Scale).magnitude() = 1.0 (g)
 */
class SensorCalibrator(context: Context) {

    data class Vec3(val x: Float, val y: Float, val z: Float) {
        operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
        operator fun times(other: Vec3) = Vec3(x * other.x, y * other.y, z * other.z) // Element-wise
        fun magnitude() = sqrt(x * x + y * y + z * z)
    }

    data class CalibrationParams(
        val bias: Vec3 = Vec3(0f, 0f, 0f),
        val scale: Vec3 = Vec3(1f, 1f, 1f)
    )

    /**
     * Full affine accelerometer model:  up = normalize(A · a_raw + b).
     *
     * A (row-major 3×3) absorbs the camera↔IMU mounting rotation, per-axis
     * scale, and cross-axis coupling; b is the bias. This is fit from the
     * 1-Shot star calibration across multiple attitudes (see
     * fit_affine_calibration in Python) and is used ONLY by the 1-Shot solve
     * path when [valid] is true. When invalid, the legacy sphere-fit + pitch/
     * roll offset path is used unchanged.
     */
    data class AffineParams(
        val a: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), // row-major
        val b: Vec3 = Vec3(0f, 0f, 0f),
        val valid: Boolean = false
    ) {
        // A data class holding a FloatArray gets reference-based equals/hashCode,
        // so two identical calibrations would compare unequal — which silently
        // breaks any Compose recomposition keyed on this value. Compare contents.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AffineParams) return false
            return valid == other.valid && b == other.b && a.contentEquals(other.a)
        }

        override fun hashCode(): Int =
            (a.contentHashCode() * 31 + b.hashCode()) * 31 + valid.hashCode()
    }

    companion object {
        private const val PREFS_NAME = "NeosextantSensors"
        private const val KEY_BIAS_X = "cal_bias_x"
        private const val KEY_BIAS_Y = "cal_bias_y"
        private const val KEY_BIAS_Z = "cal_bias_z"
        private const val KEY_SCALE_X = "cal_scale_x"
        private const val KEY_SCALE_Y = "cal_scale_y"
        private const val KEY_SCALE_Z = "cal_scale_z"

        private const val KEY_USAGE_COUNT = "cal_usage_count"
        private const val KEY_LAST_CAL_TIME = "cal_last_timestamp"

        // Affine 1-Shot calibration (12 params + validity flag)
        private const val KEY_AFFINE_VALID = "cal_affine_valid"
        private const val KEY_AFFINE_A_PREFIX = "cal_affine_a_" // a_0..a_8
        private const val KEY_AFFINE_B_PREFIX = "cal_affine_b_" // b_0..b_2
        // Quick re-zero: rotation vector (radians) applied after the affine model
        private const val KEY_REZERO_PREFIX = "cal_rezero_" // _0.._2
        private const val KEY_REZERO_TIME = "cal_rezero_timestamp"

        /** Below this many recorded poses the per-axis scale is worse than
         *  useless (see solveAlgebraicSphereFit); only the bias is applied. */
        private const val MIN_POINTS_FOR_SCALE = 16

        private const val MAX_USES_BEFORE_WARNING = 5
        private const val MAX_DAYS_BEFORE_WARNING = 10

        // Runtime sanity bounds on the affine output magnitude (m/s²). Gravity
        // is 9.78–9.83 worldwide; this allows generous headroom for residual
        // accelerometer error while still catching a broken model.
        private const val MIN_AFFINE_MAGNITUDE = 8.5f
        private const val MAX_AFFINE_MAGNITUDE = 11.0f

        /** Affine vs legacy disagreement beyond this means the fit is broken,
         *  not merely better-calibrated. */
        const val MAX_AFFINE_LEGACY_DISAGREEMENT_DEG = 10.0
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var params: CalibrationParams = loadParams()
    private var affineParams: AffineParams = loadAffineParams()

    // Temporary storage for the 8 data points during active calibration
    private val recordedPoints = mutableListOf<Vec3>()

    fun getParams() = params

    fun getAffineParams() = affineParams

    fun hasValidAffine() = affineParams.valid

    fun resetCalibration() {
        saveParams(CalibrationParams())
    }

    /** Clears the affine 1-Shot calibration, reverting the 1-Shot solve path to
     *  the legacy sphere-fit + pitch/roll offset behavior. */
    fun resetAffineCalibration() {
        saveAffineParams(AffineParams())
    }

    // --- Quick re-zero ---

    private var rezero: Vec3? = loadRezero()

    /** The re-zero rotation vector (radians), or null if none is in use. */
    fun getRezero(): Vec3? = rezero

    /** Wall-clock time of the re-zero in use, or null. */
    fun getRezeroTimeMs(): Long? =
        if (rezero == null) null else prefs.getLong(KEY_REZERO_TIME, 0L).takeIf { it > 0 }

    private fun loadRezero(): Vec3? {
        if (!prefs.contains("${KEY_REZERO_PREFIX}0")) return null
        return Vec3(
            prefs.getFloat("${KEY_REZERO_PREFIX}0", 0f),
            prefs.getFloat("${KEY_REZERO_PREFIX}1", 0f),
            prefs.getFloat("${KEY_REZERO_PREFIX}2", 0f)
        )
    }

    /** Saves a re-zero rotation fitted against the affine model in use. */
    fun saveRezero(rotation: Vec3) {
        rezero = rotation
        prefs.edit()
            .putFloat("${KEY_REZERO_PREFIX}0", rotation.x)
            .putFloat("${KEY_REZERO_PREFIX}1", rotation.y)
            .putFloat("${KEY_REZERO_PREFIX}2", rotation.z)
            .putLong(KEY_REZERO_TIME, System.currentTimeMillis())
            .apply()
        Log.d("SensorCalibrator", "Saved re-zero rotation: $rotation")
    }

    fun clearRezero() {
        rezero = null
        prefs.edit()
            .remove("${KEY_REZERO_PREFIX}0").remove("${KEY_REZERO_PREFIX}1")
            .remove("${KEY_REZERO_PREFIX}2").remove(KEY_REZERO_TIME)
            .apply()
    }

    /** Rotates [v] by the re-zero rotation vector (Rodrigues' formula). */
    private fun applyRezero(v: Vec3): Vec3 {
        val r = rezero ?: return v
        val theta = r.magnitude()
        if (theta < 1e-9f) return v
        val kx = r.x / theta; val ky = r.y / theta; val kz = r.z / theta
        val c = kotlin.math.cos(theta); val s = kotlin.math.sin(theta)
        val dot = kx * v.x + ky * v.y + kz * v.z
        return Vec3(
            v.x * c + (ky * v.z - kz * v.y) * s + kx * dot * (1 - c),
            v.y * c + (kz * v.x - kx * v.z) * s + ky * dot * (1 - c),
            v.z * c + (kx * v.y - ky * v.x) * s + kz * dot * (1 - c)
        )
    }

    // --- Persistence ---

    private fun loadParams(): CalibrationParams {
        return CalibrationParams(
            bias = Vec3(
                prefs.getFloat(KEY_BIAS_X, 0f),
                prefs.getFloat(KEY_BIAS_Y, 0f),
                prefs.getFloat(KEY_BIAS_Z, 0f)
            ),
            scale = Vec3(
                prefs.getFloat(KEY_SCALE_X, 1f),
                prefs.getFloat(KEY_SCALE_Y, 1f),
                prefs.getFloat(KEY_SCALE_Z, 1f)
            )
        )
    }

    fun saveParams(newParams: CalibrationParams) {
        params = newParams
        prefs.edit().apply {
            putFloat(KEY_BIAS_X, newParams.bias.x)
            putFloat(KEY_BIAS_Y, newParams.bias.y)
            putFloat(KEY_BIAS_Z, newParams.bias.z)
            putFloat(KEY_SCALE_X, newParams.scale.x)
            putFloat(KEY_SCALE_Y, newParams.scale.y)
            putFloat(KEY_SCALE_Z, newParams.scale.z)

            // Reset usage tracking on new calibration
            putInt(KEY_USAGE_COUNT, 0)
            putLong(KEY_LAST_CAL_TIME, System.currentTimeMillis())
            apply()
        }
        Log.d("SensorCalibrator", "Saved new calibration: $newParams")
    }

    // --- Affine Persistence ---

    private fun loadAffineParams(): AffineParams {
        val valid = prefs.getBoolean(KEY_AFFINE_VALID, false)
        if (!valid) return AffineParams()
        val a = FloatArray(9) { i -> prefs.getFloat("$KEY_AFFINE_A_PREFIX$i", if (i % 4 == 0) 1f else 0f) }
        val b = Vec3(
            prefs.getFloat("${KEY_AFFINE_B_PREFIX}0", 0f),
            prefs.getFloat("${KEY_AFFINE_B_PREFIX}1", 0f),
            prefs.getFloat("${KEY_AFFINE_B_PREFIX}2", 0f)
        )
        return AffineParams(a, b, true)
    }

    fun saveAffineParams(newParams: AffineParams) {
        affineParams = newParams
        // A re-zero is fitted against one affine model; it is wrong for any other.
        clearRezero()
        prefs.edit().apply {
            putBoolean(KEY_AFFINE_VALID, newParams.valid)
            for (i in 0 until 9) putFloat("$KEY_AFFINE_A_PREFIX$i", newParams.a[i])
            putFloat("${KEY_AFFINE_B_PREFIX}0", newParams.b.x)
            putFloat("${KEY_AFFINE_B_PREFIX}1", newParams.b.y)
            putFloat("${KEY_AFFINE_B_PREFIX}2", newParams.b.z)
            // An affine fit is a fresh calibration — reset staleness tracking too.
            putInt(KEY_USAGE_COUNT, 0)
            putLong(KEY_LAST_CAL_TIME, System.currentTimeMillis())
            apply()
        }
        Log.d("SensorCalibrator", "Saved affine calibration: valid=${newParams.valid}")
    }

    // --- Usage Tracking & Notifications ---

    fun markCalibrationUsed() {
        val count = prefs.getInt(KEY_USAGE_COUNT, 0) + 1
        prefs.edit().putInt(KEY_USAGE_COUNT, count).apply()
    }

    enum class CalibrationStatus { GOOD, NEEDS_CALIBRATION }

    fun checkCalibrationStatus(): CalibrationStatus {
        val count = prefs.getInt(KEY_USAGE_COUNT, 0)
        val lastTime = prefs.getLong(KEY_LAST_CAL_TIME, 0)
        val currentTime = System.currentTimeMillis()

        val daysSince = (currentTime - lastTime) / (1000 * 60 * 60 * 24)

        if (count > MAX_USES_BEFORE_WARNING || daysSince > MAX_DAYS_BEFORE_WARNING) {
            return CalibrationStatus.NEEDS_CALIBRATION
        }
        return CalibrationStatus.GOOD
    }


    // --- Active Calibration Session ---

    fun clearPoints() {
        recordedPoints.clear()
    }

    fun recordDataPoint(x: Float, y: Float, z: Float) {
        recordedPoints.add(Vec3(x, y, z))
        Log.d("SensorCalibrator", "Recorded point: $x, $y, $z. Total: ${recordedPoints.size}")
    }

    /**
     * Solves for Bias and Scale using an Algebraic Least Squares Sphere Fit.
     *
     * Fits the equation: (x-cx)² + (y-cy)² + (z-cz)² = R²
     * Linearized as: x² + y² + z² = Dx + Ey + Fz + G
     * where D = 2*cx, E = 2*cy, F = 2*cz, G = R² - cx² - cy² - cz²
     *
     * This is solved via the normal equations (AᵀA)x = Aᵀb.
     * Falls back to Min-Max if the algebraic solve fails.
     */
    fun solveCalibration(): CalibrationParams {
        if (recordedPoints.size < 6) {
            Log.w("SensorCalibrator", "Not enough points to solve. Returning current params.")
            return params
        }

        try {
            return solveAlgebraicSphereFit()
        } catch (e: Exception) {
            Log.w("SensorCalibrator", "Algebraic sphere fit failed, falling back to Min-Max: ${e.message}")
            return solveMinMax()
        }
    }

    /**
     * Algebraic Least Squares sphere fit.
     * Solves the overdetermined system: [x y z 1] * [D E F G]ᵀ = [x²+y²+z²]
     */
    private fun solveAlgebraicSphereFit(): CalibrationParams {
        val n = recordedPoints.size

        // Build AᵀA (4x4) and Aᵀb (4x1) incrementally to avoid large matrix allocation
        // A row = [xi, yi, zi, 1],  b_i = xi² + yi² + zi²
        val ata = Array(4) { FloatArray(4) }
        val atb = FloatArray(4)

        for (p in recordedPoints) {
            val row = floatArrayOf(p.x, p.y, p.z, 1f)
            val bi = p.x * p.x + p.y * p.y + p.z * p.z

            for (i in 0 until 4) {
                for (j in 0 until 4) {
                    ata[i][j] += row[i] * row[j]
                }
                atb[i] += row[i] * bi
            }
        }

        // Solve (AᵀA)x = Aᵀb using Gaussian elimination with partial pivoting
        val solution = solveLinearSystem4x4(ata, atb)
            ?: throw IllegalStateException("Singular matrix — degenerate point distribution")

        val d = solution[0]  // 2 * cx
        val e = solution[1]  // 2 * cy
        val f = solution[2]  // 2 * cz
        val g = solution[3]  // R² - cx² - cy² - cz²

        val cx = d / 2f
        val cy = e / 2f
        val cz = f / 2f
        val rSquared = g + cx * cx + cy * cy + cz * cz

        if (rSquared <= 0f) {
            throw IllegalStateException("Negative R² ($rSquared) — invalid sphere fit")
        }
        val r = sqrt(rSquared)

        // Per-axis scale, from the max-min RANGE of the recorded points.
        //
        // The sphere fit's CENTRE (bias) is a least-squares solve over all the
        // points and is well conditioned even from a handful. The per-axis scale
        // is not: it is an order statistic, so it only reaches the true semi-axis
        // if the user happened to land exactly on that axis. With a sloppy
        // hand-held pose the range falls short, scale = R/range comes out too
        // large, and the correction pushes the vector the wrong way.
        //
        // Simulated over a plausible sensor (1.2%/0.7%/0.6% gain errors, ~0.05
        // m/s2 bias) with poses up to 25 deg off each face, RMS direction error
        // of the corrected gravity vector:
        //
        //   poses   uncorrected   bias + range scale   bias only
        //       6       0.523            0.695           0.373
        //       8       0.523            0.755           0.364
        //      12       0.523            0.405           0.356
        //      20       0.523            0.236           0.351
        //      30       0.523            0.132           0.349
        //
        // The guided flow collects 8 poses, where applying the scale is WORSE
        // than not calibrating at all. The scale only starts paying for itself
        // around 16 poses. So it is applied only when there are enough points to
        // estimate it, and otherwise left at unity - keeping the half of the fit
        // that is trustworthy and dropping the half that is not.
        //
        // Recovering the gain terms properly is the affine model's job; it fits
        // them jointly with cross-axis coupling and gates on conditioning.
        var scaleX = 1f; var scaleY = 1f; var scaleZ = 1f
        if (n >= MIN_POINTS_FOR_SCALE) {
            val centeredX = recordedPoints.map { it.x - cx }
            val centeredY = recordedPoints.map { it.y - cy }
            val centeredZ = recordedPoints.map { it.z - cz }

            val rangeX = ((centeredX.maxOrNull() ?: r) - (centeredX.minOrNull() ?: -r)) / 2f
            val rangeY = ((centeredY.maxOrNull() ?: r) - (centeredY.minOrNull() ?: -r)) / 2f
            val rangeZ = ((centeredZ.maxOrNull() ?: r) - (centeredZ.minOrNull() ?: -r)) / 2f

            if (rangeX > 0.1f) scaleX = r / rangeX
            if (rangeY > 0.1f) scaleY = r / rangeY
            if (rangeZ > 0.1f) scaleZ = r / rangeZ
        } else {
            Log.d("SensorCalibrator",
                "Only $n points (<$MIN_POINTS_FOR_SCALE): bias-only fit, per-axis scale left at 1")
        }

        val result = CalibrationParams(
            bias = Vec3(cx, cy, cz),
            scale = Vec3(scaleX, scaleY, scaleZ)
        )
        Log.d("SensorCalibrator", "Algebraic sphere fit: center=($cx, $cy, $cz), R=$r, scale=($scaleX, $scaleY, $scaleZ)")
        return result
    }

    /**
     * Gaussian elimination with partial pivoting for a 4×4 system.
     * Returns null if the matrix is singular.
     */
    private fun solveLinearSystem4x4(a: Array<FloatArray>, b: FloatArray): FloatArray? {
        val n = 4
        // Augmented matrix
        val aug = Array(n) { i -> FloatArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }

        for (col in 0 until n) {
            // Partial pivoting
            var maxRow = col
            var maxVal = abs(aug[col][col])
            for (row in col + 1 until n) {
                val v = abs(aug[row][col])
                if (v > maxVal) { maxVal = v; maxRow = row }
            }
            if (maxVal < 1e-10f) return null // Singular
            if (maxRow != col) {
                val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp
            }

            // Eliminate below
            for (row in col + 1 until n) {
                val factor = aug[row][col] / aug[col][col]
                for (j in col until n + 1) {
                    aug[row][j] -= factor * aug[col][j]
                }
            }
        }

        // Back-substitution
        val x = FloatArray(n)
        for (i in n - 1 downTo 0) {
            var sum = aug[i][n]
            for (j in i + 1 until n) {
                sum -= aug[i][j] * x[j]
            }
            if (abs(aug[i][i]) < 1e-10f) return null
            x[i] = sum / aug[i][i]
        }
        return x
    }

    /**
     * Fallback Min-Max calibration for when the algebraic fit fails.
     */
    private fun solveMinMax(): CalibrationParams {
        val xs = recordedPoints.map { it.x }
        val ys = recordedPoints.map { it.y }
        val zs = recordedPoints.map { it.z }

        val minX = xs.minOrNull() ?: 0f; val maxX = xs.maxOrNull() ?: 0f
        val minY = ys.minOrNull() ?: 0f; val maxY = ys.maxOrNull() ?: 0f
        val minZ = zs.minOrNull() ?: 0f; val maxZ = zs.maxOrNull() ?: 0f

        val biasX = (minX + maxX) / 2f
        val biasY = (minY + maxY) / 2f
        val biasZ = (minZ + maxZ) / 2f

        // Same caveat as the algebraic fit: these ranges are order statistics and
        // only estimate the semi-axes if the poses actually reached them.
        var scaleX = 1f; var scaleY = 1f; var scaleZ = 1f
        if (recordedPoints.size >= MIN_POINTS_FOR_SCALE) {
            val rangeX = (maxX - minX) / 2f
            val rangeY = (maxY - minY) / 2f
            val rangeZ = (maxZ - minZ) / 2f
            // Use the average range as the pseudo-radius for scale normalization
            val avgRange = (rangeX + rangeY + rangeZ) / 3f
            if (rangeX > 0.1) scaleX = avgRange / rangeX
            if (rangeY > 0.1) scaleY = avgRange / rangeY
            if (rangeZ > 0.1) scaleZ = avgRange / rangeZ
        }

        Log.d("SensorCalibrator", "Min-Max fallback: bias=($biasX,$biasY,$biasZ), scale=($scaleX,$scaleY,$scaleZ)")
        return CalibrationParams(bias = Vec3(biasX, biasY, biasZ), scale = Vec3(scaleX, scaleY, scaleZ))
    }

    /**
     * Apply the calibration to a raw vector.
     */
    fun applyCalibration(raw: Vec3): Vec3 {
        // (Raw - Bias) * Scale
        val centered = raw - params.bias
        return centered * params.scale
    }

    /**
     * Apply the affine model to a raw acceleration vector:
     *   up = R · normalize(A · a_raw + b)
     * where R is the quick re-zero rotation, if one is in use.
     *
     * @param aRaw The raw acceleration vector in m/s² (direction × magnitude),
     *             NOT sphere-fit corrected — the affine model subsumes that.
     * @return The gravity/up unit direction in the sensor frame, or null if the
     *         result fails the runtime sanity check (see [affineSanityFailure]).
     */
    fun applyAffine(aRaw: Vec3): Vec3? {
        val a = affineParams.a
        val corrected = Vec3(
            a[0] * aRaw.x + a[1] * aRaw.y + a[2] * aRaw.z + affineParams.b.x,
            a[3] * aRaw.x + a[4] * aRaw.y + a[5] * aRaw.z + affineParams.b.y,
            a[6] * aRaw.x + a[7] * aRaw.y + a[8] * aRaw.z + affineParams.b.z
        )
        val mag = corrected.magnitude()
        if (mag <= 1e-6f) return null

        // Runtime sanity: a healthy affine model maps a real gravity reading back
        // onto something close to 1 g. A fit validated at calibration time can
        // still be wrong for an attitude far outside the session's coverage, and
        // the model is otherwise applied unconditionally to every future fix.
        if (mag < MIN_AFFINE_MAGNITUDE || mag > MAX_AFFINE_MAGNITUDE) {
            Log.w("SensorCalibrator",
                "Affine output |A*a+b| = $mag m/s² outside sane range; rejecting")
            return null
        }
        return applyRezero(Vec3(corrected.x / mag, corrected.y / mag, corrected.z / mag))
    }

    /**
     * Angle in degrees between the affine and legacy (sphere-fit) gravity
     * directions for the same raw reading. The affine model is *expected* to
     * differ by a degree or two — that is the point of it — but a large
     * disagreement means the calibration is broken, not merely better.
     */
    fun affineVsLegacyDeg(aRaw: Vec3): Double? {
        val affine = applyAffine(aRaw) ?: return null
        val legacy = applyCalibration(aRaw)
        val lm = legacy.magnitude()
        if (lm <= 1e-6f) return null
        val dot = (affine.x * legacy.x + affine.y * legacy.y + affine.z * legacy.z) / lm
        return Math.toDegrees(kotlin.math.acos(dot.coerceIn(-1f, 1f).toDouble()))
    }
}
