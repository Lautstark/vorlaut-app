package de.lautstark.vorlaut.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PIN is not protecting a secret — see PinHash's own note — but the parts
 * that are supposed to hold do have to hold.
 */
class PinHashTest {
    @Test
    fun `the right pin matches and a wrong one does not`() {
        val stored = PinHash.create("2468")
        assertTrue(PinHash.matches("2468", stored))
        assertFalse(PinHash.matches("2469", stored))
        assertFalse(PinHash.matches("", stored))
        assertFalse(PinHash.matches("24680", stored))
    }

    @Test
    fun `the same pin stored twice does not produce the same digest`() {
        // Per-PIN salt. Without it, two tablets with the same PIN would store
        // identical bytes, and the digest would be a lookup away from the PIN.
        val first = PinHash.create("1234")
        val second = PinHash.create("1234")
        assertNotEquals(first.salt, second.salt)
        assertNotEquals(first.digest, second.digest)
        assertTrue(PinHash.matches("1234", first))
        assertTrue(PinHash.matches("1234", second))
    }

    @Test
    fun `the pin is never stored in the clear`() {
        val stored = PinHash.create("9137")
        assertFalse("9137" in stored.digest)
        assertFalse("9137" in stored.salt)
    }

    @Test
    fun `a pin is exactly four digits`() {
        assertFalse(PinHash.isAcceptable("123"))
        assertFalse(PinHash.isAcceptable("12a4"))
        assertFalse(PinHash.isAcceptable(""))
        assertTrue(PinHash.isAcceptable("1234"))
        // Longer is no longer acceptable: the field is four boxes, so a longer
        // PIN is one the caregiver could set and then have nowhere to type.
        assertFalse(PinHash.isAcceptable("12345"))
        assertFalse(PinHash.isAcceptable("1234567"))
    }

    @Test
    fun `corrupt stored values are refused rather than crashing`() {
        // A hand-edited or truncated preferences file must lock the caregiver out
        // of the shortcut, not take the app down with it.
        assertFalse(PinHash.matches("1234", PinHash.Stored(salt = "zz", digest = "qq")))
        assertFalse(PinHash.matches("1234", PinHash.Stored(salt = "abc", digest = "def")))
    }
}
