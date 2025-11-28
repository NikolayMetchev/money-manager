# Compose UI Tests

This directory contains UI tests for the Compose components.

## Running Tests

### On Windows
Compose Desktop UI tests require graphics libraries that may not work in headless environments on Windows. If tests fail with `LibraryLoadException`, you have a few options:

1. **Run Android instrumented tests instead** (recommended for Windows):
   ```bash
   ./gradlew :android-app:connectedAndroidTest
   ```

2. **Use GitHub Actions / CI**: The tests should work fine in CI environments

3. **Use WSL2 with X Server**: Set up an X server in WSL2 for Linux-based testing

4. **Run on macOS/Linux**: These platforms handle headless Compose tests better

### On macOS/Linux
```bash
./gradlew :compose-ui:jvmTest
```

## Test Structure

The tests follow JUnit 4 conventions with Compose testing utilities:

- **ErrorScreenTest**: Tests for error display components
- **AccountsScreenTest**: Tests for account management UI including:
  - Account list display
  - Create account dialog
  - Delete account confirmation
  - User interactions (clicks, form validation)

## Writing New Tests

When writing Compose UI tests:

1. Use `@get:Rule val composeTestRule = createComposeRule()`
2. Set content with `composeTestRule.setContent {  }`
3. Use semantic finders: `onNodeWithText()`, `onNodeWithContentDescription()`
4. Assert visibility with `.assertIsDisplayed()` or `.assertDoesNotExist()`
5. Simulate interactions with `.performClick()`, `.performTextInput()`

Example:
```kotlin
@Test
fun myComponent_displaysCorrectly() {
    composeTestRule.setContent {
        MyComponent(data = testData)
    }

    composeTestRule.onNodeWithText("Expected Text").assertIsDisplayed()
}
```

## Alternative: Screenshot Testing

For more reliable cross-platform UI testing, consider using screenshot/snapshot testing libraries like:
- Paparazzi (Android)
- Shot
- Compose Screenshot Testing

These tools capture and compare UI renderings without requiring a live display.