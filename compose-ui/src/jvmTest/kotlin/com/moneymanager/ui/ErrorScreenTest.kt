package com.moneymanager.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class ErrorScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun errorScreen_displaysErrorMessage() {
        // Given
        val errorMessage = "Database connection failed"

        // When
        composeTestRule.setContent {
            ErrorScreen(
                errorMessage = errorMessage,
                fullException = null,
            )
        }

        // Then
        composeTestRule.onNodeWithText("Application Error").assertIsDisplayed()
        composeTestRule.onNodeWithText("The application failed to initialize:").assertIsDisplayed()
        composeTestRule.onNodeWithText(errorMessage).assertIsDisplayed()
    }

    @Test
    fun errorScreen_displaysFullException_whenProvided() {
        // Given
        val errorMessage = "Database connection failed"
        val fullException = "java.sql.SQLException: Unable to connect to database at localhost:5432"

        // When
        composeTestRule.setContent {
            ErrorScreen(
                errorMessage = errorMessage,
                fullException = fullException,
            )
        }

        // Then
        composeTestRule.onNodeWithText("Exception Details:").assertIsDisplayed()
        composeTestRule.onNodeWithText(fullException, substring = true).assertIsDisplayed()
    }

    @Test
    fun errorScreen_doesNotDisplayExceptionDetails_whenNotProvided() {
        // Given
        val errorMessage = "Database connection failed"

        // When
        composeTestRule.setContent {
            ErrorScreen(
                errorMessage = errorMessage,
                fullException = null,
            )
        }

        // Then
        composeTestRule.onNodeWithText("Exception Details:").assertDoesNotExist()
    }

    @Test
    fun errorScreen_truncatesLongExceptions() {
        // Given
        val errorMessage = "Database connection failed"
        val longException = "x".repeat(600) // 600 characters

        // When
        composeTestRule.setContent {
            ErrorScreen(
                errorMessage = errorMessage,
                fullException = longException,
            )
        }

        // Then - should display first 500 characters plus "..."
        composeTestRule.onNodeWithText("Exception Details:").assertIsDisplayed()
        // The text should be truncated to 500 chars + "..."
        val expectedTruncated = longException.take(500) + "..."
        composeTestRule.onNodeWithText(expectedTruncated).assertIsDisplayed()
    }

    @Test
    fun errorScreen_displaysMultilineErrorMessage() {
        // Given
        val errorMessage = "Database connection failed\nPlease check your connection settings"

        // When
        composeTestRule.setContent {
            ErrorScreen(
                errorMessage = errorMessage,
                fullException = null,
            )
        }

        // Then
        composeTestRule.onNodeWithText(errorMessage).assertIsDisplayed()
    }

    @Test
    fun errorScreen_handlesEmptyErrorMessage() {
        // Given
        val errorMessage = ""

        // When
        composeTestRule.setContent {
            ErrorScreen(
                errorMessage = errorMessage,
                fullException = null,
            )
        }

        // Then - should still display the screen structure
        composeTestRule.onNodeWithText("Application Error").assertIsDisplayed()
        composeTestRule.onNodeWithText("The application failed to initialize:").assertIsDisplayed()
    }
}
