# Compose UI Tests

This directory contains multiplatform UI tests for the Compose components using `runComposeUiTest`.

## Running Tests

### All Platforms
```bash
# Run all common tests (JVM, Android, etc.)
./gradlew :compose-ui:test

# Run JVM-specific tests
./gradlew :compose-ui:jvmTest

# Run Android tests (requires emulator or device)
./gradlew :compose-ui:testDebugUnitTest
```

### On Windows
Compose Desktop UI tests may have issues running locally on Windows due to Skiko graphics library limitations. If tests fail locally:

1. **CI/CD is the primary target**: Tests should work fine in GitHub Actions CI (Linux runners)
2. **Use WSL2**: Run tests in WSL2 for better compatibility
3. **macOS/Linux**: These platforms handle headless Compose tests natively

## Test Architecture

The tests use **`runComposeUiTest`** - the multiplatform approach for Compose UI testing:

```kotlin
@Test
fun myTest() = runComposeUiTest {
    setContent {
        MyComponent(data = testData)
    }

    onNodeWithText("Expected").assertIsDisplayed()
}
```

### Advantages of `runComposeUiTest`:
- ✅ **Platform-agnostic**: Works across JVM, Android, and future platforms
- ✅ **No JUnit dependency**: Uses `kotlin.test` annotations
- ✅ **Modern API**: Similar to `runTest` for coroutines
- ✅ **Simpler syntax**: No test rules needed

## Test Structure

- **ErrorScreenTest**: Tests for error display components (6 tests)
- **AccountsScreenTest**: Tests for account management UI (11 tests)
  - Account list display (empty/populated states)
  - Create account dialog
  - Delete account confirmation
  - User interactions (clicks, form validation)
  - Fake repository for isolated testing

## Writing New Tests

### Basic Test Pattern
```kotlin
@OptIn(ExperimentalTestApi::class)
class MyComponentTest {
    @Test
    fun myComponent_displaysCorrectly() = runComposeUiTest {
        // Given
        val testData = ...

        // When
        setContent {
            MyComponent(data = testData)
        }

        // Then
        onNodeWithText("Expected Text").assertIsDisplayed()
        onNodeWithText("Button").performClick()
        onNodeWithText("Result").assertIsDisplayed()
    }
}
```

### Common Assertions
- `.assertIsDisplayed()` - Element is visible
- `.assertDoesNotExist()` - Element is not in the tree
- `.assertIsEnabled()` / `.assertIsNotEnabled()` - Check enabled state
- `.performClick()` - Simulate click
- `.performTextInput("text")` - Type text into field

### Fake Repositories
Use fake implementations for isolated testing:

```kotlin
private class FakeRepository : MyRepository {
    private val data = MutableStateFlow<List<Item>>(emptyList())

    override fun getData(): Flow<List<Item>> = data

    override suspend fun create(item: Item) {
        data.value = data.value + item
    }
}
```

## Best Practices

1. **Test behavior, not implementation**: Focus on what users see and do
2. **Use semantic finders**: Prefer `onNodeWithText()` over `onNodeWithTag()`
3. **Keep tests isolated**: Each test should be independent
4. **Use fake dependencies**: Mock repositories/services for predictable results
5. **Test user flows**: Simulate actual user interactions

## Platform-Specific Tests

If you need platform-specific tests:
- `jvmTest/`: JVM-only tests
- `androidUnitTest/` or `androidInstrumentedTest/`: Android-only tests

But prefer `commonTest/` for shared UI components!