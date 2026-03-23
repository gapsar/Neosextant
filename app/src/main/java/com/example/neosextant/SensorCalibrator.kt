package com.example.neosextant

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

        private const val MAX_USES_BEFORE_WARNING = 5
        private const val MAX_DAYS_BEFORE_WARNING = 10
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var params: CalibrationParams = loadParams()

    // Temporary storage for the 6+ data points during active calibration
    private val recordedPoints = mutableListOf<Vec3>()

    fun getParams() = params

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

        // Derive per-axis scale: compute the range in each axis from the fitted sphere
        // and scale to match the fitted radius R (not 9.81).
        // This reshapes the measurement ellipsoid into a true sphere before
        // normalization in SensorPipeline extracts the direction.
        val targetR = r

        // Per-axis effective radius: for each axis, the range of centered data gives the
        // semi-axis length. For a perfect sphere they'd all equal R.
        val centeredX = recordedPoints.map { it.x - cx }
        val centeredY = recordedPoints.map { it.y - cy }
        val centeredZ = recordedPoints.map { it.z - cz }

        val rangeX = ((centeredX.maxOrNull() ?: r) - (centeredX.minOrNull() ?: -r)) / 2f
        val rangeY = ((centeredY.maxOrNull() ?: r) - (centeredY.minOrNull() ?: -r)) / 2f
        val rangeZ = ((centeredZ.maxOrNull() ?: r) - (centeredZ.minOrNull() ?: -r)) / 2f

        val scaleX = if (rangeX > 0.1f) targetR / rangeX else 1f
        val scaleY = if (rangeY > 0.1f) targetR / rangeY else 1f
        val scaleZ = if (rangeZ > 0.1f) targetR / rangeZ else 1f

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

        val targetG = 9.81f
        val rangeX = (maxX - minX) / 2f
        val rangeY = (maxY - minY) / 2f
        val rangeZ = (maxZ - minZ) / 2f
        // Use the average range as the pseudo-radius for scale normalization
        val avgRange = (rangeX + rangeY + rangeZ) / 3f

        val scaleX = if (rangeX > 0.1) avgRange / rangeX else 1f
        val scaleY = if (rangeY > 0.1) avgRange / rangeY else 1f
        val scaleZ = if (rangeZ > 0.1) avgRange / rangeZ else 1f

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
}
