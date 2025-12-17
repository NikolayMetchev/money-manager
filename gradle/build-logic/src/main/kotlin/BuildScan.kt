import com.gradle.develocity.agent.gradle.test.ImportJUnitXmlReports
import com.gradle.develocity.agent.gradle.test.JUnitXmlDialect
import org.gradle.api.Project

/**
 * Registers ImportJUnitXmlReports tasks for Android device tests to enable
 * test result visibility in Gradle Build Scans.
 *
 * This integrates with Develocity to import JUnit XML reports from Android
 * instrumented tests (managed devices and connected devices) into the build scan.
 */
internal fun Project.configureAndroidTestBuildScan(managedDeviceNames: List<String>) {
    afterEvaluate {
        managedDeviceNames.forEach { deviceName ->
            val testTaskName = "${deviceName}AndroidDeviceTest"
            tasks.findByName(testTaskName)?.let {
                ImportJUnitXmlReports.register(
                    tasks,
                    tasks.named(testTaskName),
                    JUnitXmlDialect.GENERIC,
                )
            }
        }
    }
}