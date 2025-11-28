package com.moneymanager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ErrorScreenTest {
    @Test
    fun errorScreen_displaysErrorMessage() = runComposeUiTest {
        // Given
        val errorMessage = "Database connection failed"

        // When
        setContent {
            ErrorScreen(
                errorMessage = errorMessage,
                fullException = null,
            )
        }

        // Then
        onNodeWithText("Application Error").assertIsDisplayed()
        onNodeWithText("The application failed to initialize:").assertIsDisplayed()
        onNodeWithText(errorMessage).assertIsDisplayed()
    }

    @Test
    fun errorScreen_displaysFullException_whenProvided() = runComposeUiTest {
        // Given
        val errorMessage = "Database connection failed"
        val fullException = "java.sql.SQLException: Unable to connect to database at localhost:5432"

        // When
        setContent {
            ErrorScreen(
                errorMessage = errorMessage,
                fullException = fullException,
            )
        }

        // Then
        onNodeWithText("Exception Details:").assertIsDisplayed()
        onNodeWithText(fullException, substring = true).assertIsDisplayed()
    }

    @Test
    fun errorScreen_doesNotDisplayExceptionDetails_whenNotProvided() = runComposeUiTest {
        // Given
        val errorMessage = "Database connection failed"

        // When
        setContent {
            ErrorScreen(
                errorMessage = errorMessage,
                fullException = null,
            )
        }

        // Then
        onNodeWithText("Exception Details:").assertDoesNotExist()
    }

    @Test
    fun errorScreen_truncatesLongExceptions() = runComposeUiTest {
        // Given
        val errorMessage = "Database connection failed"
        val longException = "x".repeat(600) // 600 characters

        // When
        setContent {
            ErrorScreen(
                errorMessage = errorMessage,
                fullException = longException,
            )
        }

        // Then - should display first 500 characters plus "..."
        onNodeWithText("Exception Details:").assertIsDisplayed()
        // The text should be truncated to 500 chars + "..."
        val expectedTruncated = longException.take(500) + "..."
        onNodeWithText(expectedTruncated).assertIsDisplayed()
    }

    @Test
    fun errorScreen_displaysMultilineErrorMessage() = runComposeUiTest {
        // Given
        val errorMessage = "Database connection failed\nPlease check your connection settings"

        // When
        setContent {
            ErrorScreen(
                errorMessage = errorMessage,
                fullException = null,
            )
        }

        // Then
        onNodeWithText(errorMessage).assertIsDisplayed()
    }

    @Test
    fun errorScreen_handlesEmptyErrorMessage() = runComposeUiTest {
        // Given
        val errorMessage = ""

        // When
        setContent {
            ErrorScreen(
                errorMessage = errorMessage,
                fullException = null,
            )
        }

        // Then - should still display the screen structure
        onNodeWithText("Application Error").assertIsDisplayed()
        onNodeWithText("The application failed to initialize:").assertIsDisplayed()
    }
}
