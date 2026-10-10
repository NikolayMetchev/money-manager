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

# Ignore warnings for platform-specific Compose/Skiko dependencies
# These differ between macOS, Linux, and Windows
-dontwarn org.jetbrains.skia.**
-dontwarn org.jetbrains.skiko.**

# Apache POI + XMLBeans (utils/parsers/xlsx) only ever read .xlsx. Everything reached from our code
# is kept by ordinary shrinking; these rules cover what they load by name, which ProGuard can't see.
# Anything else in them (HSSF, slideshows, Word, charts, signatures, the XMLBeans compiler) is dropped.

# WorkbookFactory finds XSSFWorkbookFactory (and the HSSF one) through ServiceLoader
-keep class * implements org.apache.poi.ss.usermodel.WorkbookProvider { *; }
# POI's other service providers (text extraction, slideshows, image rendering) are never looked up.
# Their no-arg constructors keep verifyProguardServiceProviders happy without keeping those features.
-keep class * implements org.apache.poi.extractor.ExtractorProvider { public <init>(); }
-keep class * implements org.apache.poi.sl.draw.ImageRenderer { public <init>(); }
-keep class * implements org.apache.poi.sl.usermodel.MetroShapeProvider { public <init>(); }
-keep class * implements org.apache.poi.sl.usermodel.SlideShowProvider { public <init>(); }

# XMLBeans' runtime resolves its built-in types and each schema's TypeSystemHolder by name. The
# excluded packages are its schema compiler and command-line tooling.
-keep class !org.apache.xmlbeans.impl.tool.**,!org.apache.xmlbeans.impl.config.**,!org.apache.xmlbeans.impl.inst2xsd.**,!org.apache.xmlbeans.impl.xsd2inst.**,!org.apache.xmlbeans.impl.schema.Stsc*,!org.apache.xmlbeans.impl.schema.SchemaTypeSystemCompiler*,org.apache.xmlbeans.** { *; }
-keep class **.TypeSystemHolder { *; }

# XMLBeans instantiates the implementation of each schema type it parses by name, through its
# (SchemaType[, boolean]) constructor: <package>.<Name> -> <package>.impl.<Name>Impl, and nested
# <Outer>$<Inner> -> <Outer>Impl$<Inner>Impl. Keep those constructors for every schema interface our
# code reaches; the impl methods it calls through the interface are then kept by ordinary shrinking.
# Keeping whole classes instead would pull in every type their accessors mention (Word, slides...).
# A type nothing references (which is how poi-ooxml-lite itself works) parses as a generic XmlObject.
# Also by reflection: string-enum values from <Type>$Enum's static `table` field, and a type's
# SchemaType from its interface's static `type` field (XmlBeans.typeForClass)
-keepclassmembers class * extends org.apache.xmlbeans.StringEnumAbstractBase { *; }
-keepclassmembers interface * extends org.apache.xmlbeans.XmlObject { public static final org.apache.xmlbeans.SchemaType type; }
-if interface org.apache.poi.schemas.**.*
-keep class org.apache.poi.schemas.<1>.impl.<2>Impl, org.apache.poi.schemas.<1>.impl.<2>Impl$** { <init>(org.apache.xmlbeans.SchemaType, ...); }
-if interface org.apache.poi.schemas.**.*$*
-keep class org.apache.poi.schemas.<1>.impl.<2>Impl, org.apache.poi.schemas.<1>.impl.<2>Impl$** { <init>(org.apache.xmlbeans.SchemaType, ...); }
-if interface org.openxmlformats.schemas.**.*
-keep class org.openxmlformats.schemas.<1>.impl.<2>Impl, org.openxmlformats.schemas.<1>.impl.<2>Impl$** { <init>(org.apache.xmlbeans.SchemaType, ...); }
-if interface org.openxmlformats.schemas.**.*$*
-keep class org.openxmlformats.schemas.<1>.impl.<2>Impl, org.openxmlformats.schemas.<1>.impl.<2>Impl$** { <init>(org.apache.xmlbeans.SchemaType, ...); }
-if interface com.microsoft.schemas.**.*
-keep class com.microsoft.schemas.<1>.impl.<2>Impl, com.microsoft.schemas.<1>.impl.<2>Impl$** { <init>(org.apache.xmlbeans.SchemaType, ...); }
-if interface com.microsoft.schemas.**.*$*
-keep class com.microsoft.schemas.<1>.impl.<2>Impl, com.microsoft.schemas.<1>.impl.<2>Impl$** { <init>(org.apache.xmlbeans.SchemaType, ...); }
-if interface org.etsi.uri.**.*
-keep class org.etsi.uri.<1>.impl.<2>Impl, org.etsi.uri.<1>.impl.<2>Impl$** { <init>(org.apache.xmlbeans.SchemaType, ...); }
-if interface org.etsi.uri.**.*$*
-keep class org.etsi.uri.<1>.impl.<2>Impl, org.etsi.uri.<1>.impl.<2>Impl$** { <init>(org.apache.xmlbeans.SchemaType, ...); }
-if interface org.w3.x2000.**.*
-keep class org.w3.x2000.<1>.impl.<2>Impl, org.w3.x2000.<1>.impl.<2>Impl$** { <init>(org.apache.xmlbeans.SchemaType, ...); }
-if interface org.w3.x2000.**.*$*
-keep class org.w3.x2000.<1>.impl.<2>Impl, org.w3.x2000.<1>.impl.<2>Impl$** { <init>(org.apache.xmlbeans.SchemaType, ...); }

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
# also bound to its native library over JNI.
-keep class org.sqlite.** { *; }
-keep class dev.whyoleg.cryptography.** { *; }
-keep class io.ktor.client.engine.cio.CIOEngineContainer { *; }

# Log4j. log4j-api and the SLF4J bridge are small and found through ServiceLoader and by name.
-keep class !org.apache.logging.log4j.core.**,org.apache.logging.log4j.** { *; }
-keep class org.apache.logging.slf4j.** { *; }
# log4j-core instantiates much of itself by name (context selectors, clocks, config factories) and every
# plugin from the Log4j2Plugins.dat descriptor, so it's kept whole except for plugin families that
# log4j2.xml never names: database/messaging/network/scripting appenders, non-pattern layouts, filters,
# async loggers, and the OSGi/CLI tooling. A descriptor entry whose class was removed is skipped with
# an INFO-level status message; a config naming one would fail loudly at startup.
-keep class !org.apache.logging.log4j.core.appender.db.**,!org.apache.logging.log4j.core.appender.mom.**,!org.apache.logging.log4j.core.appender.nosql.**,!org.apache.logging.log4j.core.appender.routing.**,!org.apache.logging.log4j.core.appender.rewrite.**,!org.apache.logging.log4j.core.appender.AsyncAppender*,!org.apache.logging.log4j.core.appender.Failover*,!org.apache.logging.log4j.core.appender.Http*,!org.apache.logging.log4j.core.appender.MemoryMapped*,!org.apache.logging.log4j.core.appender.RandomAccessFile*,!org.apache.logging.log4j.core.appender.RollingRandomAccessFile*,!org.apache.logging.log4j.core.appender.ScriptAppenderSelector*,!org.apache.logging.log4j.core.appender.SmtpAppender*,!org.apache.logging.log4j.core.appender.SocketAppender*,!org.apache.logging.log4j.core.appender.SyslogAppender*,!org.apache.logging.log4j.core.appender.TlsSyslogFrame*,!org.apache.logging.log4j.core.appender.CountingNoOpAppender*,!org.apache.logging.log4j.core.layout.*Csv*,!org.apache.logging.log4j.core.layout.*Jackson*,!org.apache.logging.log4j.core.layout.GelfLayout*,!org.apache.logging.log4j.core.layout.HtmlLayout*,!org.apache.logging.log4j.core.layout.JsonLayout*,!org.apache.logging.log4j.core.layout.LoggerFields*,!org.apache.logging.log4j.core.layout.MessageLayout*,!org.apache.logging.log4j.core.layout.Rfc5424Layout*,!org.apache.logging.log4j.core.layout.ScriptPatternSelector*,!org.apache.logging.log4j.core.layout.SerializedLayout*,!org.apache.logging.log4j.core.layout.SyslogLayout*,!org.apache.logging.log4j.core.layout.XmlLayout*,!org.apache.logging.log4j.core.layout.YamlLayout*,!org.apache.logging.log4j.core.jackson.**,!org.apache.logging.log4j.core.filter.**,!org.apache.logging.log4j.core.async.**,!org.apache.logging.log4j.core.net.**,!org.apache.logging.log4j.core.script.**,!org.apache.logging.log4j.core.config.json.**,!org.apache.logging.log4j.core.config.yaml.**,!org.apache.logging.log4j.core.config.properties.**,!org.apache.logging.log4j.core.config.plugins.processor.**,!org.apache.logging.log4j.core.osgi.**,!org.apache.logging.log4j.core.tools.**,org.apache.logging.log4j.core.** { *; }
# The excluded classes are still kept when something reaches them, but left unoptimized as before:
# ProGuard's optimizer can't analyse the Jackson modules, whose superclasses (Jackson) aren't bundled.
-keep,allowshrinking class org.apache.logging.log4j.core.** { *; }
