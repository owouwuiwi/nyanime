package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.NyanimeMark
import eu.kanade.presentation.theme.NyanimeWordmark
import eu.kanade.tachiyomi.R

@Composable
fun LogoHeader(compact: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NyanimeMark(
            modifier = Modifier
                .padding(top = if (compact) 8.dp else 32.dp, bottom = 8.dp)
                .size(if (compact) 44.dp else 64.dp),
        )

        NyanimeWordmark(Modifier.padding(bottom = if (compact) 16.dp else 24.dp))
        HorizontalDivider()
    }
}
