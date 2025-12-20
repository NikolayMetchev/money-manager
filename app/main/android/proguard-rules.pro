# ProGuard rules for MoneyManager Android application

# Keep Android entry point
-keep class com.moneymanager.android.MainActivity { *; }

# Suppress warnings for Android platform internals referenced by libres plurals
-dontwarn libcore.icu.NativePluralRules