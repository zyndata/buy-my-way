package dev.gorny.buymyway.spike

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Phase 0 spike. One APK on both phones; account A uses the "A" buttons, account B the "B"
 * buttons. Results go to the on-screen log and to `adb logcat -s BMW-SPIKE`.
 * Developer-facing only, so the labels are English.
 */
class MainActivity : ComponentActivity() {
    private var accessToken: String? = null
    private val drive = Drive { accessToken ?: error("Authorize Drive first") }
    private var status by mutableStateOf("signed out")
    private var shareWith by mutableStateOf("")
    private var folderId: String? = null
    private var fileId: String? = null

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        runCatching { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(r.data) }
            .onSuccess(::onAuthorized)
            .onFailure { SpikeLog.i("Drive consent failed: $it") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (BuildConfig.FIREBASE_APP_ID.isEmpty()) SpikeLog.i("spike.properties is missing, Firebase is not initialised")
        else Rtdb.trackServerOffset()
        FirebaseAuth.getInstance().currentUser?.let { status = "Firebase: ${it.email}" }
        setContent { MaterialTheme { Surface { Screen() } } }
    }

    private fun run(label: String, block: suspend () -> Unit) {
        lifecycleScope.launch {
            SpikeLog.i("> $label")
            try { block() } catch (e: Exception) { SpikeLog.i("FAILED $label: $e") }
        }
    }

    private val uid get() = FirebaseAuth.getInstance().currentUser?.uid ?: error("Sign in first")

    // --- identity -------------------------------------------------------------------------

    private suspend fun signIn() {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.WEB_CLIENT_ID).build()
        val result = CredentialManager.create(this)
            .getCredential(this, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val google = GoogleIdTokenCredential.createFrom(result.credential.data)
        val user = FirebaseAuth.getInstance()
            .signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await().user
        status = "Firebase: ${user?.email}"
        SpikeLog.i("Signed in as ${user?.email} uid=${user?.uid}")
    }

    private suspend fun authorize() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE), Scope(DRIVE_APPDATA)))
            .build()
        val result = Identity.getAuthorizationClient(this).authorize(request).await()
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        else onAuthorized(result)
    }

    private fun onAuthorized(result: AuthorizationResult) {
        accessToken = result.accessToken
        SpikeLog.i("Drive authorized, granted scopes: ${result.grantedScopes}")
        status = status.substringBefore(" +") + " + Drive"
    }

    // --- account A --------------------------------------------------------------------------

    private suspend fun aCreate() {
        val root = drive.createFolder("Buy My Way", null)
        val folder = drive.createFolder("spike", root)
        val content = """{"v":1,"writtenBy":"A","at":${System.currentTimeMillis()}}"""
        val file = drive.createFile("list.json", folder, content)
        folderId = folder
        fileId = file
        Rtdb.putHandoff(folder, file, uid)
        SpikeLog.i("Created Buy My Way/spike/list.json (folder=$folder, file=$file); ids handed off via RTDB")
    }

    private suspend fun aShare() {
        val folder = folderId ?: Rtdb.getHandoff()?.first ?: error("Create first")
        SpikeLog.i("Shared folder: ${drive.share(folder, shareWith.trim(), "writer")}")
    }

    private suspend fun readFile() {
        val file = fileId ?: Rtdb.getHandoff()?.second ?: error("No file id")
        SpikeLog.i("Metadata: ${drive.metadata(file)}")
        SpikeLog.i("Content: ${drive.read(file)}")
    }

    // --- account B --------------------------------------------------------------------------

    private suspend fun bFetchHandoff() {
        val (folder, file) = Rtdb.getHandoff() ?: error("No handoff in RTDB, run A1 first")
        folderId = folder
        fileId = file
        SpikeLog.i("Handoff: folder=$folder file=$file")
    }

    private suspend fun bList() {
        val folder = folderId ?: error("Fetch the handoff first")
        SpikeLog.i("files.list in folder: ${drive.list("'$folder' in parents and trashed = false")}")
    }

    private suspend fun bUpdate() {
        val file = fileId ?: error("Fetch the handoff first")
        val content = """{"v":1,"writtenBy":"B","at":${System.currentTimeMillis()}}"""
        SpikeLog.i("files.update: ${drive.update(file, content)}")
    }

    private suspend fun bDiscover() {
        // Informative only: the app gets the folder id from RTDB meta and never needs this.
        SpikeLog.i("sharedWithMe folders: ${drive.list("sharedWithMe and mimeType = 'application/vnd.google-apps.folder'")}")
    }

    // --- both -------------------------------------------------------------------------------

    private suspend fun appData() {
        val id = drive.createFile("prefs.json", "appDataFolder", """{"at":${System.currentTimeMillis()}}""")
        SpikeLog.i("appDataFolder create: $id")
        SpikeLog.i("appDataFolder list: ${drive.list("name = 'prefs.json'", spaces = "appDataFolder")}")
        SpikeLog.i("appDataFolder read: ${drive.read(id)}")
    }

    private suspend fun driveTiming() {
        val folder = folderId ?: Rtdb.getHandoff()?.first ?: error("Create first")
        val payload = buildString {
            append("{\"pad\":\"")
            while (length < 20 * 1024 - 2) append('x')
            append("\"}")
        }
        val id = drive.createFile("timing-20k.json", folder, payload)
        val samples = (1..10).map {
            val t0 = SystemClock.elapsedRealtime()
            val size = drive.read(id).length
            (SystemClock.elapsedRealtime() - t0).also { ms -> SpikeLog.i("files.get 20 kB #$it: $ms ms ($size B)") }
        }
        SpikeLog.i(stats("Drive files.get 20 kB", samples))
    }

    private suspend fun copyFcmToken() {
        val token = FirebaseMessaging.getInstance().token.await()
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("fcm", token))
        SpikeLog.i("FCM token copied to the clipboard (paste into the script property FCM_TOKEN)")
    }

    private suspend fun pushToSelf() {
        val idToken = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token ?: error("Sign in first")
        Push.send(idToken)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen() {
        val lines by SpikeLog.lines.collectAsState()
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
            Text(status, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(shareWith, { shareWith = it }, label = { Text("A2: share with (email of B)") }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Btn("Sign in") { signIn() }
                Btn("Authorize Drive") { authorize() }
                Btn("A1 create") { aCreate() }
                Btn("A2 share") { aShare() }
                Btn("Read file") { readFile() }
                Btn("B1 handoff") { bFetchHandoff() }
                Btn("B2 list") { bList() }
                Btn("B4 update") { bUpdate() }
                Btn("B5 sharedWithMe") { bDiscover() }
                Btn("appdata") { appData() }
                Btn("Drive 20 kB x10") { driveTiming() }
                Btn("RTDB responder") { Rtdb.toggleResponder(uid) }
                Btn("RTDB ping x20") { Rtdb.pingRun(uid) }
                Btn("RTDB clear") { Rtdb.clear() }
                Btn("Copy FCM token") { copyFcmToken() }
                Btn("Push to self") { pushToSelf() }
                Btn("Push stats") { SpikeLog.i(Push.summary()) }
            }
            SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
    }

    @Composable
    private fun Btn(label: String, action: suspend () -> Unit) {
        Button(onClick = { run(label, action) }) { Text(label, fontSize = 12.sp) }
    }

    companion object {
        const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    }
}
