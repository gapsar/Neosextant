package io.github.gapsar.neosextant

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opportunistically fetches accurate time from NTP and keeps it for offline use
 * during celestial navigation. 1 s of clock error is 0.25 nmi of longitude.
 *
 * The sync is anchored to the monotonic elapsedRealtime clock, not to the
 * phone's wall clock: true time = true time at sync + time elapsed since. The
 * wall clock can be stepped at any moment (network or cell time, the user), and
 * an offset measured against it would then silently double-count the step.
 * elapsedRealtime restarts at boot, so after a reboot the wall-clock offset
 * measured at the last sync is the fallback.
 *
 * Accuracy: sub-100 ms on a reasonable network, bounded by half the round trip.
 */
object TimeSynchronizer {
    private const val PREFS_NAME = "NeosextantTime"
    private const val KEY_OFFSET = "time_offset_millis"
    private const val KEY_SYNC_TIME_WALL = "sync_time_wall"
    private const val KEY_SYNC_TIME_ELAPSED = "sync_time_elapsed"
    private const val KEY_SYNC_TRUE_TIME = "sync_true_time"
    private const val KEY_SYNC_BOOT_COUNT = "sync_boot_count"

    private const val NTP_HOST = "time.google.com"
    private const val NTP_PORT = 123
    private const val NTP_TIMEOUT_MS = 3000

    /** Network delay above which half of it is too large an error to accept. */
    private const val MAX_NTP_DELAY_MS = 2000L

    /** Seconds between the NTP epoch (1900) and the Unix epoch (1970). */
    private const val NTP_EPOCH_OFFSET_S = 2208988800L

    /** Any sync claiming to be earlier than this (2024-01-01) is garbage. */
    private const val MIN_PLAUSIBLE_TIME_MS = 1_704_067_200_000L

    /**
     * One NTP sync. [trueTimeMs] was the true time when elapsedRealtime read
     * [elapsedMs], during boot [bootCount]. [wallOffsetMs] is true time minus
     * the wall clock at that moment, used only after a reboot.
     */
    private data class Anchor(
        val trueTimeMs: Long,
        val elapsedMs: Long,
        val bootCount: Int,
        val wallOffsetMs: Long,
        val wallTimeMs: Long
    )

    @Volatile private var anchor: Anchor? = null
    @Volatile private var currentBootCount: Int = -1
    @Volatile private var loaded = false
    private val syncInFlight = AtomicBoolean(false)

    fun sync(context: Context) {
        val appContext = context.applicationContext
        currentBootCount = readBootCount(appContext)
        if (!loaded) {
            anchor = loadAnchor(appContext)
            loaded = true
            Log.d("TimeSynchronizer", "Initialized with stored sync: $anchor")
        }
        // Called from every onResume; one query at a time is plenty.
        if (!syncInFlight.compareAndSet(false, true)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = queryNtp()
                if (result == null) {
                    Log.d("TimeSynchronizer", "NTP sync failed; keeping existing sync $anchor")
                    return@launch
                }
                val nowWall = System.currentTimeMillis()
                val nowElapsed = SystemClock.elapsedRealtime()
                val trueNow = result.trueTimeMs + (nowElapsed - result.elapsedMs)
                val newAnchor = Anchor(
                    trueTimeMs = result.trueTimeMs,
                    elapsedMs = result.elapsedMs,
                    bootCount = currentBootCount,
                    wallOffsetMs = trueNow - nowWall,
                    wallTimeMs = nowWall
                )
                anchor = newAnchor
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_SYNC_TRUE_TIME, newAnchor.trueTimeMs)
                    .putLong(KEY_SYNC_TIME_ELAPSED, newAnchor.elapsedMs)
                    .putInt(KEY_SYNC_BOOT_COUNT, newAnchor.bootCount)
                    .putLong(KEY_OFFSET, newAnchor.wallOffsetMs)
                    .putLong(KEY_SYNC_TIME_WALL, newAnchor.wallTimeMs)
                    .apply()
                Log.d("TimeSynchronizer", "Synced via NTP: wall clock offset " +
                    "${newAnchor.wallOffsetMs} ms, network delay ${result.delayMs} ms")
            } finally {
                syncInFlight.set(false)
            }
        }
    }

    private fun loadAnchor(context: Context): Anchor? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wallTime = prefs.getLong(KEY_SYNC_TIME_WALL, 0L)
        if (wallTime == 0L) return null
        return Anchor(
            // Syncs stored before the elapsed anchor existed have no true time or
            // boot count; they load as "different boot" and use the wall offset.
            trueTimeMs = prefs.getLong(KEY_SYNC_TRUE_TIME, 0L),
            elapsedMs = prefs.getLong(KEY_SYNC_TIME_ELAPSED, 0L),
            bootCount = prefs.getInt(KEY_SYNC_BOOT_COUNT, -1),
            wallOffsetMs = prefs.getLong(KEY_OFFSET, 0L),
            wallTimeMs = wallTime
        )
    }

    private fun readBootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /** True if [a] was taken during this boot, so its elapsedRealtime is still comparable. */
    private fun isSameBoot(a: Anchor, nowElapsedMs: Long): Boolean =
        a.trueTimeMs != 0L && a.bootCount != -1 && a.bootCount == currentBootCount &&
            nowElapsedMs >= a.elapsedMs

    private class NtpResult(val trueTimeMs: Long, val elapsedMs: Long, val delayMs: Long)

    /**
     * One NTP exchange. Returns the true time at the instant the reply arrived,
     * or null if the reply is missing or fails validation. A bad reply must never
     * be stored: at 0.25 nmi of longitude per second, one garbage timestamp
     * would wreck every fix until the next successful sync.
     */
    private fun queryNtp(): NtpResult? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = NTP_TIMEOUT_MS
            val address = InetAddress.getByName(NTP_HOST)

            val request = ByteArray(48)
            request[0] = 0x1B // LI 0, version 3, mode 3 (client)
            // Random transmit timestamp: the server must echo it back as the
            // originate timestamp, which rejects stale or unrelated packets.
            val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
            nonce.copyInto(request, 40)

            val sentElapsed = SystemClock.elapsedRealtime()
            socket.send(DatagramPacket(request, request.size, address, NTP_PORT))
            val reply = ByteArray(48)
            val packet = DatagramPacket(reply, reply.size)
            socket.receive(packet)
            val receivedElapsed = SystemClock.elapsedRealtime()

            val leap = (reply[0].toInt() shr 6) and 0x3
            val mode = reply[0].toInt() and 0x7
            val stratum = reply[1].toInt() and 0xFF
            val problem = when {
                packet.length < 48 -> "short reply (${packet.length} bytes)"
                mode != 4 -> "not a server reply (mode $mode)"
                leap == 3 -> "server clock unsynchronized"
                stratum !in 1..15 -> "bad stratum $stratum (0 = kiss-of-death)"
                !reply.copyOfRange(24, 32).contentEquals(nonce) -> "originate timestamp mismatch"
                read32(reply, 40) == 0L -> "zero transmit timestamp"
                else -> null
            }
            if (problem != null) {
                Log.w("TimeSynchronizer", "Rejected NTP reply: $problem")
                return@use null
            }

            val serverReceived = readTimeStamp(reply, 32)
            val serverSent = readTimeStamp(reply, 40)
            // Round trip minus the time the server itself spent on the request.
            val delay = (receivedElapsed - sentElapsed) - (serverSent - serverReceived)
            val trueAtReceipt = serverSent + delay / 2
            when {
                serverSent < serverReceived || delay < 0 || delay > MAX_NTP_DELAY_MS -> {
                    Log.w("TimeSynchronizer", "Rejected NTP reply: implausible delay $delay ms")
                    null
                }
                trueAtReceipt < MIN_PLAUSIBLE_TIME_MS -> {
                    Log.w("TimeSynchronizer", "Rejected NTP reply: implausible time $trueAtReceipt")
                    null
                }
                else -> NtpResult(trueAtReceipt, receivedElapsed, delay)
            }
        }
    } catch (e: Exception) {
        Log.d("TimeSynchronizer", "NTP Sync Exception: ${e.message}")
        null
    }

    private fun readTimeStamp(buffer: ByteArray, offset: Int): Long {
        val seconds = read32(buffer, offset)
        val fraction = read32(buffer, offset + 4)
        return ((seconds - NTP_EPOCH_OFFSET_S) * 1000L) + ((fraction * 1000L) / 0x100000000L)
    }

    private fun read32(buffer: ByteArray, offset: Int): Long {
        val b0 = buffer[offset].toLong() and 0xFF
        val b1 = buffer[offset + 1].toLong() and 0xFF
        val b2 = buffer[offset + 2].toLong() and 0xFF
        val b3 = buffer[offset + 3].toLong() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    /** Best estimate of true time now. */
    fun getTrueTime(): Date = Date(trueTimeAtElapsedMillis(SystemClock.elapsedRealtime()))

    /**
     * True time at a past instant given on the SystemClock.elapsedRealtimeNanos
     * clock — used to timestamp a photo at its exposure midpoint rather than at
     * whenever the JPEG finished being written.
     */
    fun getTrueTimeAtElapsedNanos(elapsedNs: Long): Date =
        Date(trueTimeAtElapsedMillis(elapsedNs / 1_000_000L))

    private fun trueTimeAtElapsedMillis(elapsedMs: Long): Long {
        val nowElapsed = SystemClock.elapsedRealtime()
        val a = anchor
        return when {
            a == null -> System.currentTimeMillis() - (nowElapsed - elapsedMs)
            isSameBoot(a, nowElapsed) -> a.trueTimeMs + (elapsedMs - a.elapsedMs)
            // Rebooted since the sync: the monotonic anchor is gone, fall back to
            // the wall clock plus the offset measured when we last synced.
            else -> System.currentTimeMillis() - (nowElapsed - elapsedMs) + a.wallOffsetMs
        }
    }

    /**
     * Age of the last sync in milliseconds, and whether it is measured on the
     * monotonic clock (true) or had to use the wall clock because the device
     * rebooted since (false). Null if never synced.
     */
    fun getSyncAgeMillis(): Pair<Long, Boolean>? {
        val a = anchor ?: return null
        val nowElapsed = SystemClock.elapsedRealtime()
        return if (isSameBoot(a, nowElapsed)) {
            Pair(nowElapsed - a.elapsedMs, true)
        } else {
            Pair(System.currentTimeMillis() - a.wallTimeMs, false)
        }
    }
}
