package io.github.queryparams.overtakelink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun `numeric parts compare as numbers, not text`() {
        assertTrue(UpdateCheck.newer("0.10", "0.9"))
        assertTrue(UpdateCheck.newer("1.0", "0.9.9"))
        assertTrue(UpdateCheck.newer("0.6.1", "0.6"))
    }

    @Test
    fun `same or older version is not an update`() {
        assertFalse(UpdateCheck.newer("0.6", "0.6"))
        assertFalse(UpdateCheck.newer("0.6", "0.6.0"))
        assertFalse(UpdateCheck.newer("0.5", "0.6"))
    }

    @Test
    fun `suffixes from older builds are ignored`() {
        assertTrue(UpdateCheck.newer("0.6", "0.5-overtakelink"))
        assertFalse(UpdateCheck.newer("0.6", "0.6-beta"))
    }
}
