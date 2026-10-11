package eu.kanade.presentation.browse.store

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.extensionstore.StoreLanguages
import eu.kanade.tachiyomi.util.system.LocaleHelper
import java.util.Locale

@Composable
internal fun StoreFilterOptions(
    languages: Set<String>,
    adult: Boolean,
    onLanguage: (String) -> Unit,
    onAdult: (Boolean) -> Unit,
) {
    val colors = LocalStoreColors.current
    val context = LocalContext.current
    val motion = modernMotionEnabled()
    Text(stringResource(R.string.store_language_hint), color = colors.muted, fontSize = 14.sp, lineHeight = 20.sp)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StoreLanguages.options.forEach { language ->
            val checked = language in languages
            val background by animateColorAsState(
                if (checked) colors.tint else colors.elevated,
                tween(if (motion) ModernMotion.PAGE_MILLIS else 0),
                label = "storeLanguageSurface",
            )
            Surface(
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = { onLanguage(language) },
                ),
                shape = RoundedCornerShape(20.dp),
                color = background,
                border = BorderStroke(1.dp, if (checked) colors.accent.copy(alpha = 0.55f) else colors.line),
            ) {
                Row(
                    Modifier.heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(shape = RoundedCornerShape(12.dp), color = colors.background) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Text(language.uppercase(Locale.ROOT), fontWeight = FontWeight.Bold, color = colors.text)
                        }
                    }
                    Text(
                        LocaleHelper.getSourceDisplayName(language, context),
                        Modifier.weight(1f),
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.muted),
                    )
                }
            }
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = colors.elevated) {
        Row(
            Modifier.fillMaxWidth().toggleable(adult, role = Role.Switch, onValueChange = onAdult)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Security, null, tint = colors.muted, modifier = Modifier.size(26.dp))
            Text(stringResource(R.string.store_adult), Modifier.weight(1f), color = colors.text, fontSize = 15.sp)
            Switch(adult, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent))
        }
    }
    Text(
        stringResource(R.string.store_installed_filter_hint),
        color = colors.muted,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
}
