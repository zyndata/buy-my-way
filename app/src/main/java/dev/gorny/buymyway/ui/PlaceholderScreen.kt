package dev.gorny.buymyway.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.gorny.buymyway.R

/** A screen that only shows its Polish title and the links that lead further in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(
    @StringRes title: Int,
    onBack: (() -> Unit)? = null,
    links: List<Pair<Int, () -> Unit>> = emptyList(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    if (onBack != null) {
                        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.placeholder_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            links.forEach { (label, onClick) ->
                OutlinedButton(onClick = onClick) { Text(stringResource(label)) }
            }
        }
    }
}
