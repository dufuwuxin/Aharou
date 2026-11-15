package com.aharou.feature.settings.data.account

import com.google.gson.JsonObject
import java.util.Base64

/** DeepSeekHashV1 uses SHA3 padding and Keccak rounds 1..23 (round zero is omitted). */
internal object DeepSeekAccountPow {
    private val rotations = intArrayOf(0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14)

    // Generate the Keccak round constants from its 8-bit LFSR, independently of provider code.
    private val constants = LongArray(24).also { values ->
        var register = 1
        for (round in values.indices) {
            var value = 0L
            for (bit in 0..6) {
                if (register and 1 != 0) value = value xor (1L shl ((1 shl bit) - 1))
                register = ((register shl 1) xor (if (register and 128 != 0) 0x71 else 0)) and 255
            }
            values[round] = value
        }
    }

    fun digest(input: ByteArray): ByteArray {
        val padded = input.copyOf((input.size / 136 + 1) * 136)
        padded[input.size] = 6
        padded[padded.lastIndex] = (padded.last().toInt() or 128).toByte()
        val state = LongArray(25)
        val columns = LongArray(5)
        val moved = LongArray(25)
        for (offset in padded.indices step 136) {
            for (i in 0 until 136) state[i / 8] = state[i / 8] xor ((padded[offset + i].toLong() and 255) shl (8 * (i % 8)))
            for (round in 1..23) {
                for (x in 0..4) columns[x] = state[x] xor state[x + 5] xor state[x + 10] xor state[x + 15] xor state[x + 20]
                for (x in 0..4) {
                    val d = columns[(x + 4) % 5] xor java.lang.Long.rotateLeft(columns[(x + 1) % 5], 1)
                    for (y in 0..4) state[x + 5 * y] = state[x + 5 * y] xor d
                }
                for (x in 0..4) for (y in 0..4) {
                    moved[y + 5 * ((2 * x + 3 * y) % 5)] = java.lang.Long.rotateLeft(state[x + 5 * y], rotations[x + 5 * y])
                }
                for (x in 0..4) for (y in 0..4) {
                    state[x + 5 * y] = moved[x + 5 * y] xor (moved[(x + 1) % 5 + 5 * y].inv() and moved[(x + 2) % 5 + 5 * y])
                }
                state[0] = state[0] xor constants[round]
            }
        }
        return ByteArray(32) { (state[it / 8] ushr (8 * (it % 8))).toByte() }
    }

    fun header(challenge: JsonObject, checkActive: () -> Unit, now: () -> Long = System::currentTimeMillis): String {
        fun invalid(): Nothing = throw AccountProviderException("invalid_pow", "Invalid or unsupported DeepSeek challenge")
        if (challenge.text("algorithm") != "DeepSeekHashV1") invalid()
        val salt = challenge.text("salt")?.takeIf { it.length <= 4096 } ?: invalid()
        val signature = challenge.text("signature")?.takeIf { it.length <= 8192 } ?: invalid()
        val expires = challenge.text("expire_at")?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 } ?: invalid()
        val difficulty = challenge.text("difficulty")?.toLongOrNull()?.takeIf { it in 1..5_000_000 } ?: invalid()
        val hex = challenge.text("challenge")?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) } ?: invalid()
        if (challenge.text("target_path") != null && challenge.text("target_path") != DeepSeekAccountProtocol.COMPLETION) invalid()
        val target = ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun active() {
            checkActive()
            if (now() >= expires * 1000) throw AccountProviderException("pow_expired", "DeepSeek challenge expired", 408)
        }
        for (answer in 0 until difficulty) {
            active()
            if (!digest("${salt}_${expires}_$answer".toByteArray(Charsets.UTF_8)).contentEquals(target)) continue
            active()
            return Base64.getEncoder().encodeToString(JsonObject().apply {
                addProperty("algorithm", "DeepSeekHashV1"); addProperty("challenge", hex)
                addProperty("salt", salt); addProperty("answer", answer)
                addProperty("signature", signature); addProperty("target_path", DeepSeekAccountProtocol.COMPLETION)
            }.toString().toByteArray(Charsets.UTF_8))
        }
        throw AccountProviderException("pow_unsolved", "DeepSeek challenge has no solution in its declared range")
    }
}
