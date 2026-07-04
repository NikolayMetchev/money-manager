package com.moneymanager.compose.filepicker

import kotlin.test.Test
import kotlin.test.assertEquals

class FolderPickerAndroidTest {
    @Test
    fun displayNameFromDocumentId_usesLastPathSegment() {
        assertEquals("2024", displayNameFromDocumentId("primary:Statements/2024"))
    }

    @Test
    fun displayNameFromDocumentId_handlesRootOfVolume() {
        assertEquals("Statements", displayNameFromDocumentId("primary:Statements"))
    }

    @Test
    fun displayNameFromDocumentId_fallsBackToWholeIdWhenEmpty() {
        assertEquals("primary:", displayNameFromDocumentId("primary:"))
    }
}
