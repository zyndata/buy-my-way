package dev.gorny.buymyway.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.gorny.buymyway.core.update.Updates
import dev.gorny.buymyway.data.update.ApkDownloads
import dev.gorny.buymyway.data.update.AppUpdates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The update banner's state (PLAN.md Phase 10, task 3). One instance for the whole app: the
 * banner on Listy and „Sprawdź aktualizacje" in „O aplikacji" are two views of it.
 *
 * [downloads] is null wherever there is no phone to download onto — the tests — and then the
 * banner shows and dismisses exactly as it does on a device, but „Pobierz" does nothing.
 */
class UpdateViewModel(
    private val updates: AppUpdates,
    private val downloads: ApkDownloads? = null,
) : ViewModel() {
    /** What „Pobierz" is doing, if anything. */
    sealed interface Download {
        data object Idle : Download

        data object Running : Download

        /** Android has not been told this app may install apps: the user is sent to Settings. */
        data object NotAllowed : Download

        data object Failed : Download
    }

    /** What „Sprawdź aktualizacje" answered, once. Null while nothing has been said. */
    enum class Checked { UP_TO_DATE, FOUND, FAILED }

    val update: StateFlow<Updates.Release?> get() = updates.update

    private val downloadState = MutableStateFlow<Download>(Download.Idle)
    val download: StateFlow<Download> = downloadState.asStateFlow()

    private val checkedState = MutableStateFlow<Checked?>(null)
    val checked: StateFlow<Checked?> = checkedState.asStateFlow()

    private val checkingState = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = checkingState.asStateFlow()

    init {
        // Once per start: the APK this version was installed from has done its job.
        downloads?.let { viewModelScope.launch(Dispatchers.IO) { it.removeStale() } }
    }

    /** Listy was shown. At most one call a day reaches GitHub (STATE.md decision 108). */
    fun onScreenShown() {
        viewModelScope.launch { updates.checkIfDue() }
    }

    /** „Sprawdź aktualizacje": ask now, and say what came back. */
    fun checkNow() {
        if (checkingState.value) return
        checkingState.value = true
        checkedState.value = null
        viewModelScope.launch {
            val outcome = updates.check()
            checkingState.value = false
            checkedState.value = when (outcome) {
                is AppUpdates.Outcome.Newer -> Checked.FOUND
                AppUpdates.Outcome.UpToDate -> Checked.UP_TO_DATE
                AppUpdates.Outcome.Unreachable -> Checked.FAILED
            }
        }
    }

    fun answerShown() {
        checkedState.value = null
    }

    fun dismiss() {
        viewModelScope.launch { updates.dismiss() }
    }

    /**
     * „Pobierz". Returns the intent the screen is to start, if any: a Settings screen when
     * Android has not been told this app may install apps, and the installer once the APK is
     * here. The view model never starts an activity itself.
     */
    fun downloadAndInstall(onIntent: (android.content.Intent) -> Unit) {
        val downloads = downloads ?: return
        val release = update.value ?: return
        if (downloadState.value == Download.Running) return
        if (!downloads.canInstall()) {
            downloadState.value = Download.NotAllowed
            onIntent(downloads.unknownSourcesSettings())
            return
        }
        val id = downloads.enqueue(release)
        if (id == null) {
            downloadState.value = Download.Failed
            return
        }
        downloadState.value = Download.Running
        viewModelScope.launch {
            when (val progress = downloads.awaitFinish(id)) {
                is ApkDownloads.Progress.Done -> {
                    downloadState.value = Download.Idle
                    onIntent(downloads.installIntent(progress.apk))
                }
                else -> downloadState.value = Download.Failed
            }
        }
    }
}
