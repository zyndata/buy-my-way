package dev.gorny.buymyway.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gorny.buymyway.R

/**
 * „Dostępna wersja X — Pobierz" on Listy (PLAN.md Phase 10, task 3). Shown only once a check
 * has found a newer release than the one running, and only until „Ukryj" — which hides that
 * version, not the next one.
 */
@Composable
fun UpdateBanner(vm: UpdateViewModel, onIntent: (android.content.Intent) -> Unit) {
    val release by vm.update.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val available = release ?: return

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .testTag("update-banner"),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                stringResource(R.string.update_available, available.version.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            when (download) {
                UpdateViewModel.Download.NotAllowed -> Hint(R.string.update_allow_install)
                UpdateViewModel.Download.Failed -> Hint(R.string.update_failed)
                else -> Unit
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = vm::dismiss) { Text(stringResource(R.string.action_hide)) }
                TextButton(
                    onClick = { vm.downloadAndInstall(onIntent) },
                    enabled = download != UpdateViewModel.Download.Running,
                ) {
                    Text(
                        stringResource(
                            if (download == UpdateViewModel.Download.Running) {
                                R.string.update_downloading
                            } else {
                                R.string.action_download
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(top = 4.dp),
    )
}
