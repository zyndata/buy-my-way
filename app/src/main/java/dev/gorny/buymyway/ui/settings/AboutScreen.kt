package dev.gorny.buymyway.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gorny.buymyway.BuildConfig
import dev.gorny.buymyway.R
import dev.gorny.buymyway.ui.update.UpdateViewModel
import kotlinx.coroutines.launch

/**
 * „O aplikacji" (STATE.md decision 105): the version, the licences and where the code lives —
 * what used to sit at the bottom of Ustawienia, on a screen of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    /** „Sprawdź aktualizacje" (PLAN.md Phase 10, task 3); absent where the screen is tested alone. */
    updates: UpdateViewModel? = null,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val messages = rememberCoroutineScope()
    val source = stringResource(R.string.about_source_value)
    val failed = stringResource(R.string.about_source_failed)
    val checking = updates?.checking?.collectAsStateWithLifecycle()?.value ?: false
    val checked = updates?.checked?.collectAsStateWithLifecycle()?.value
    val upToDate = stringResource(R.string.about_up_to_date)
    val found = stringResource(R.string.about_check_found)
    val checkFailed = stringResource(R.string.about_check_failed)

    // What the check answered, said once.
    LaunchedEffect(checked) {
        val answer = when (checked) {
            UpdateViewModel.Checked.UP_TO_DATE -> upToDate
            UpdateViewModel.Checked.FOUND -> found
            UpdateViewModel.Checked.FAILED -> checkFailed
            null -> return@LaunchedEffect
        }
        // Shown first, cleared after: clearing it changes `checked`, which restarts this very
        // effect and would cancel the `showSnackbar` before anything reached the screen.
        snackbar.showSnackbar(answer)
        // Non-null: `checked` came from it (the compiler knows, so a safe call is a warning).
        updates.answerShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .testTag("about"),
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp),
            )
            Text(
                stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_version)) },
                supportingContent = { Text(BuildConfig.VERSION_NAME) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_licence)) },
                supportingContent = { Text(stringResource(R.string.about_licence_value)) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_icons)) },
                supportingContent = { Text(stringResource(R.string.about_icons_value)) },
            )
            if (updates != null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_check_updates)) },
                    supportingContent = if (checking) {
                        { Text(stringResource(R.string.about_checking)) }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .clickable(enabled = !checking, onClick = updates::checkNow)
                        .testTag("check-updates"),
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_source)) },
                supportingContent = { Text(source) },
                modifier = Modifier.clickable {
                    // A phone with no browser at all simply says so; nothing else depends on it.
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://$source".toUri()))
                    } catch (_: ActivityNotFoundException) {
                        messages.launch { snackbar.showSnackbar(failed) }
                    }
                },
            )
        }
    }
}
