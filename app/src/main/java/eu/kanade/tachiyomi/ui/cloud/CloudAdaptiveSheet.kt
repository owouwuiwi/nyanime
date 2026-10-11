package eu.kanade.tachiyomi.ui.cloud

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.ui.cloud.cloudMotionEnabled as appMotionEnabled

/**
 * Sheet with adaptive position aligned to bottom on small screen, otherwise aligned to center
 * and will not be able to dismissed with swipe gesture.
 *
 * Max width of the content is set to 460 dp.
 */
@Composable
internal fun CloudAdaptiveSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    enableSwipeDismiss: Boolean = true,
    content: @Composable () -> Unit,
) {
    val isTabletUi = isTabletUi()
    val modern = LocalNyanimeStyle.current
    val motion = appMotionEnabled()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = dialogProperties,
    ) {
        CloudSheetContent(
            modifier = modifier,
            isTabletUi = isTabletUi,
            enableSwipeDismiss = enableSwipeDismiss,
            onDismissRequest = onDismissRequest,
            animationDurationMillis = CloudMotion.duration(motion, CloudMotion.SURFACE_ENTER_MILLIS),
            dismissAnimationDurationMillis = CloudMotion.duration(motion, CloudMotion.SURFACE_EXIT_MILLIS),
            animationEasing = CloudMotion.SmoothOut,
            surfaceScale = if (motion && modern) CloudMotion.SURFACE_SCALE else 1f,
            waitForDismissAnimation = modern,
        ) {
            content()
        }
    }
}

private val dialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = true,
)
