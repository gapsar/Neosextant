package com.example.neosextant

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object AstroUtils {

    private const val DEG2RAD = Math.PI / 180.0
    private const val RAD2DEG = 180.0 / Math.PI

    // This data class and function are no longer strictly necessary for the LOP calculation
    // path which now uses calibrated phonePitchDeg directly.
    // Kept if other parts of the app might need a 3-axis based pointing vector calculation.
    data class FullCameraPointing(val hvDeg: Double, val zvDeg: Double)

    fun normalizeAngle0To360(degrees: Double): Double {
        var result = degrees % 360.0
        if (result < 0) {
            result += 360.0
        }
        return result
    }

    /**
     * Calculates the Height (Hv) and Azimuth (Zv) of the camera's pointing direction,
     * based on the phone's full 3-axis orientation.
     * Note: This function's output is NOT directly used for LOP Ho calculation anymore,
     * as calibrated phonePitchDeg is used instead.
     */
    fun getFullCameraPointingHvZv(
        phoneAzimuthDeg: Double?,
        phonePitchDeg: Double?,
        phoneRollDeg: Double?
    ): FullCameraPointing? {
        if (phoneAzimuthDeg == null || phonePitchDeg == null || phoneRollDeg == null) {
            return null
        }

        val azPhoneRad = phoneAzimuthDeg * DEG2RAD
        val pitchPhoneRad = phonePitchDeg * DEG2RAD
        val rollPhoneRad = phoneRollDeg * DEG2RAD

        val negAzPhoneRad = -azPhoneRad
        val negPitchPhoneRad = -pitchPhoneRad

        val ca = cos(negAzPhoneRad)
        val sa = sin(negAzPhoneRad)
        val cp = cos(negPitchPhoneRad)
        val sp = sin(negPitchPhoneRad)
        val cr = cos(rollPhoneRad)
        val sr = sin(rollPhoneRad)

        val vWorldN = ca * sp * cr + sa * sr
        val vWorldE = sa * sp * cr - ca * sr
        val vWorldZ = -cp * cr // Component along Zenith

        val hvRad = asin(vWorldZ.coerceIn(-1.0, 1.0))
        // Original convention was +90. Standard astro altitude would be just (hvRad * RAD2DEG).
        // Let's assume standard astronomical altitude (0 horizon, 90 zenith) if this were to be used.
        // For direct use of pitch, this function is less relevant.
        // If phone is flat (pitch=0, roll=0), camera on back points up, vWorldZ = -1. asin(-1) = -PI/2.
        // For camera pointing to Zenith, Hv should be 90.
        // The original +90 might have been to adjust for a different definition of vWorldZ.
        // Given current usage, let's use a standard definition if this were to be used.
        // Standard Z component of pointing vector (device +Z): -rotationMatrix[8] from MainActivity
        // If device +Z is camera axis, and points to Zenith, its Z component is 1. asin(1) = 90 deg.
        // The vWorldZ here is for world Z in device frame.
        // For camera (device +Z) pointing to world Zenith: vWorldZ = -1. hv_rad = asin(-1) = -pi/2.
        // To get astronomical altitude (90 deg), need -hv_rad or adjust axes.
        // The original AstroUtils output for hvDeg = (hvRad * RAD2DEG) + 90;
        // if pitch=0, roll=0 (phone flat, camera up), vWorldZ = -1, hvRad = -PI/2, hvDeg = 0. This means 0 for Zenith.
        // If pitch=90 (phone vertical, camera horizontal), vWorldZ = 0, hvRad = 0, hvDeg = 90. This means 90 for Horizon.
        // This is inverted and shifted. Let's leave it as it was for this specific calculation,
        // but acknowledge it's not standard Ho.
        val hvDeg = (hvRad * RAD2DEG) + 90.0


        val zvRad = atan2(vWorldE, vWorldN)
        val zvDeg = normalizeAngle0To360(zvRad * RAD2DEG)

        return FullCameraPointing(hvDeg, zvDeg)
    }
}