# ProGuard rules for MoneyManager Desktop application

# Keep main entry point
-keep class com.moneymanager.MainKt { *; }

# Ignore warnings for optional log4j dependencies that aren't used
-dontwarn com.lmax.disruptor.**
-dontwarn org.jctools.**
-dontwarn com.fasterxml.jackson.**
-dontwarn javax.mail.**
-dontwarn javax.jms.**
-dontwarn javax.activation.**
-dontwarn org.osgi.**
-dontwarn aQute.bnd.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.zeromq.**
-dontwarn org.apache.kafka.**
-dontwarn org.apache.commons.**
-dontwarn com.conversantmedia.**
-dontwarn org.codehaus.stax2.**

# Ignore warnings for log4j classes that reference optional dependencies
-dontwarn org.apache.logging.log4j.**

# Ignore warnings for diamondedge logging optional dependency
-dontwarn kotlinx.datetime.**

# Ignore warnings for platform-specific Compose/Skiko dependencies
# These differ between macOS, Linux, and Windows
-dontwarn org.jetbrains.skia.**
-dontwarn org.jetbrains.skiko.**