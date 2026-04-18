package com.moneymanager.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun `sha256Hex returns lowercase SHA-256 digest`() {
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            sha256Hex("hello world"),
        )
    }
}
