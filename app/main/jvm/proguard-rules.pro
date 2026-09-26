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

# Apache POI + XMLBeans (utils/parsers/xlsx). Both resolve classes by name at runtime:
# WorkbookFactory loads XSSFWorkbookFactory reflectively and XMLBeans finds each schema type's
# generated implementation through its TypeSystemHolder, so shrinking them breaks .xlsx import.
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.schemas.** { *; }
-keep class com.microsoft.schemas.** { *; }
-keep class org.etsi.uri.** { *; }
-keep class org.w3.x2000.** { *; }

# poi-ooxml-lite ships only the schema types POI itself uses, so the rest of each schema's
# references dangle by design
-dontwarn org.openxmlformats.schemas.**
-dontwarn com.microsoft.schemas.**
-dontwarn org.etsi.uri.**
-dontwarn org.w3.x2000.**

# Optional POI/XMLBeans features we never touch: SVG/PDF rendering, XML signatures, and the
# XMLBeans build tooling (Ant/Maven plugins, source generation)
-dontwarn org.apache.batik.**
-dontwarn org.apache.pdfbox.**
-dontwarn de.rototor.pdfbox.**
-dontwarn org.apache.jcp.**
-dontwarn org.apache.xml.**
-dontwarn org.apache.tools.ant.**
-dontwarn org.apache.maven.**
-dontwarn com.github.javaparser.**
-dontwarn net.sf.saxon.**
-dontwarn org.apache.xmlbeans.impl.tool.**
-dontwarn org.apache.xmlbeans.impl.config.**
-dontwarn org.apache.poi.xslf.draw.**
-dontwarn org.apache.poi.poifs.nio.CleanerUtil

# BouncyCastle is an optional provider for both POI (signatures) and whyoleg cryptography; the
# app uses the JDK provider only
-dontwarn org.bouncycastle.**

# Libraries found at runtime through META-INF/services (java.util.ServiceLoader), which ProGuard
# doesn't follow: shrinking removes the provider classes and the lookup then fails. sqlite-jdbc is
# also bound to its native library over JNI, and log4j-core instantiates its plugins reflectively.
-keep class org.sqlite.** { *; }
-keep class dev.whyoleg.cryptography.** { *; }
-keep class io.ktor.client.engine.cio.CIOEngineContainer { *; }
-keep class org.apache.logging.log4j.** { *; }
-keep class org.apache.logging.slf4j.** { *; }
