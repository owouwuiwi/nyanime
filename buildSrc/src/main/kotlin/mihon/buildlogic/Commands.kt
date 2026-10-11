package mihon.buildlogic

import org.gradle.api.Project
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Git is needed in your system PATH for these commands to work.
// If it's not installed, you can return a random value as a workaround
fun Project.getCommitCount(): String {
    return runCommand("git rev-list --count HEAD")
    // return "1"
}

fun Project.getGitSha(): String {
    return runCommand("git rev-parse --short HEAD")
    // return "1"
}

private val BUILD_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

/**
 * @param useLastCommitTime If `true`, the build time is based on the timestamp of the last Git commit;
 *                          otherwise, SOURCE_DATE_EPOCH or the commit timestamp is used. Both are in UTC.
 * @return A formatted string representing the build time. The format used is defined by [BUILD_TIME_FORMATTER].
 */
fun Project.getBuildTime(useLastCommitTime: Boolean): String {
    val epoch = if (useLastCommitTime) {
        runCommand("git log -1 --format=%ct").toLong()
    } else {
        providers.environmentVariable("SOURCE_DATE_EPOCH").orNull?.let {
            requireNotNull(it.toLongOrNull()?.takeIf { value -> value >= 0 }) {
                "SOURCE_DATE_EPOCH must be a nonnegative Unix timestamp"
            }
        }
    }
    // Wall-clock time invalidates BuildConfig (and its consumers) on every no-change build.
    return Instant.ofEpochSecond(epoch ?: runCommand("git log -1 --format=%ct").toLong())
        .atOffset(ZoneOffset.UTC).format(BUILD_TIME_FORMATTER)
}

private fun Project.runCommand(command: String): String {
    return providers.exec {
        commandLine = command.split(" ")
    }
        .standardOutput
        .asText
        .get()
        .trim()
}
