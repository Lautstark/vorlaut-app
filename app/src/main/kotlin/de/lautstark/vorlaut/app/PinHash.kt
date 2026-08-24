package de.lautstark.vorlaut.app

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Storage form for the handover PIN.
 *
 * **What this is for, and what it is not.** The PIN keeps a child — or anyone
 * picking the tablet up — from wandering out of the board and into the rest of
 * the device. That is the whole threat model. It is not protecting a secret, and
 * a four-digit PIN cannot be made to: anyone who can read the app's private
 * storage can try all ten thousand of them.
 *
 * It is still stored as a salted PBKDF2 digest rather than in the clear, because
 * a PIN written in plain text in a file is one that gets read over somebody's
 * shoulder, copied into a bug report, or reused — and people reuse the PIN they
 * use elsewhere however often they are told not to.
 *
 * Kept free of Android types so it can be tested on the JVM.
 */
object PinHash {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    /** The shortest PIN worth calling one. */
    const val MIN_LENGTH = 4

    data class Stored(
        val salt: String,
        val digest: String,
    )

    fun create(pin: String): Stored {
        require(isAcceptable(pin)) { "a PIN must be at least $MIN_LENGTH digits" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        return Stored(salt = encode(salt), digest = encode(derive(pin, salt)))
    }

    fun matches(
        pin: String,
        stored: Stored,
    ): Boolean {
        val salt = decode(stored.salt) ?: return false
        val expected = decode(stored.digest) ?: return false
        // Constant-time: a comparison that returns early leaks how much of the
        // digest matched. It matters little at four digits, and costs nothing.
        return MessageDigest.isEqual(derive(pin, salt), expected)
    }

    fun isAcceptable(pin: String): Boolean = pin.length >= MIN_LENGTH && pin.all { it.isDigit() }

    private fun derive(
        pin: String,
        salt: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun decode(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        return runCatching {
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }
}
