package com.moneymanager.ui.screens.apistrategy

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Why API downloads may not work in this front end, or null where they do. The browser app provides one: most
 * providers' APIs refuse requests from a web page (CORS), so the API screens say so rather than fail mysteriously.
 */
val LocalApiDownloadNotice = staticCompositionLocalOf<String?> { null }

/** Shows [LocalApiDownloadNotice], if the front end gave one. */
@Composable
internal fun ApiDownloadNoticeBanner() {
    val notice = LocalApiDownloadNotice.current ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Text(notice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}
