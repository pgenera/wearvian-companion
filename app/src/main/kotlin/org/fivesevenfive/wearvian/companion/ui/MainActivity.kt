package org.fivesevenfive.wearvian.companion.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.fivesevenfive.wearvian.companion.util.DebugLog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.fivesevenfive.wearvian.companion.BuildConfig
import org.fivesevenfive.wearvian.companion.ui.EnrollViewModel.UiState
import org.fivesevenfive.wearvian.companion.util.logi

class MainActivity : ComponentActivity() {

    private val vm: EnrollViewModel by viewModels()
    private val importVm: ImportViewModel by viewModels()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            logi("POST_NOTIFICATIONS granted=$granted")
        }

    private fun launchScanner() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let { importVm.onQrScanned(it) } }
            .addOnCanceledListener { logi("QR scan cancelled") }
            .addOnFailureListener { logi("QR scan failed: ${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logi("MainActivity: onCreate")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            WearvianTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by vm.state.collectAsStateWithLifecycle()
                    val importState by importVm.state.collectAsStateWithLifecycle()
                    EnrollScreen(
                        state = state,
                        importState = importState,
                        onCredentials = vm::submitCredentials,
                        onOtp = vm::submitOtp,
                        onReset = vm::reset,
                        onImport = ::launchScanner,
                        onImportReset = importVm::reset,
                    )
                }
            }
        }
    }
}

/** Taps on the bottom half needed to reveal the hidden HA-key import button. */
private const val SECRET_TAPS = 5

@Composable
private fun EnrollScreen(
    state: UiState,
    importState: ImportViewModel.UiState,
    onCredentials: (String, String, Boolean) -> Unit,
    onOtp: (String) -> Unit,
    onReset: () -> Unit,
    onImport: () -> Unit,
    onImportReset: () -> Unit,
) {
    var showImport by remember { mutableStateOf(false) }
    var taps by remember { mutableIntStateOf(0) }

    // targetSdk 35 enforces edge-to-edge: inset the content so nothing draws under the system bars.
    // Split vertically: the normal UI on top, an on-screen log pane docked in the bottom half so the
    // enroll/import flow is debuggable on a device without adb.
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Hidden gesture: 5 taps on the title reveals the HA-key import button. (The bottom half
                // is the log pane now, so the reveal lives on the title — a concrete, reliable tap target.)
                Text(
                    "wearvian companion",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures { if (!showImport && ++taps >= SECRET_TAPS) showImport = true }
                    },
                )
                when (state) {
                    is UiState.Waiting -> Text(
                        "Waiting for your watch. Open wearvian on your Pixel Watch and tap " +
                            "“Enroll” to begin.",
                    )

                    is UiState.NeedCredentials -> CredentialsForm(state.watchName, state.rememberedEmail, onCredentials)

                    is UiState.MfaRequired -> OtpForm(state.email, onOtp)

                    is UiState.Working -> {
                        CircularProgressIndicator()
                        Text(state.message)
                    }

                    is UiState.Done -> {
                        Text("Enrolled with ${state.vehicleCount} vehicle(s). Your watch can now unlock and drive your Rivian offline.")
                        Button(onClick = onReset) { Text("Done") }
                    }

                    is UiState.Failed -> {
                        Text("Enrollment failed: ${state.error}", color = MaterialTheme.colorScheme.error)
                        Button(onClick = onReset) { Text("Try again") }
                    }
                }

                if (showImport) ImportSection(importState, onImport, onImportReset)
            }
            // Show the git branch only when it isn't main (or unknown), so a feature build is obvious.
            val branchSuffix = BuildConfig.GIT_BRANCH
                .takeIf { it.isNotBlank() && it != "main" }
                ?.let { " · $it" }
                .orEmpty()
            Text(
                text = "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TIME}$branchSuffix",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            )
        }
        // The log pane is part of the hidden debug surface: it appears only once the import button
        // is revealed (5 taps on the title), so the normal screen stays clean.
        if (showImport) LogPane(modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

/** On-screen log pane (bottom half) fed by [DebugLog] — newest at the bottom, auto-scrolled. */
@Composable
private fun LogPane(modifier: Modifier = Modifier) {
    val lines by DebugLog.flow.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Logs", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { DebugLog.clear() }) { Text("Clear") }
            }
            HorizontalDivider()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Hidden debug section: import an existing key (e.g. from Home Assistant) via QR. */
@Composable
private fun ImportSection(
    importState: ImportViewModel.UiState,
    onImport: () -> Unit,
    onReset: () -> Unit,
) {
    HorizontalDivider()
    Text("Import key", style = MaterialTheme.typography.titleMedium)
    when (importState) {
        is ImportViewModel.UiState.Idle ->
            OutlinedButton(onClick = onImport) { Text("Scan HA import QR") }

        is ImportViewModel.UiState.Working -> {
            CircularProgressIndicator()
            Text(importState.message)
        }

        is ImportViewModel.UiState.Done -> {
            Text("Imported key for ${importState.vin}. Open wearvian on your watch to pair.")
            Button(onClick = onReset) { Text("Done") }
        }

        is ImportViewModel.UiState.Failed -> {
            Text("Import failed: ${importState.error}", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { onReset(); onImport() }) { Text("Try again") }
        }
    }
}

@Composable
private fun CredentialsForm(watchName: String, initialEmail: String, onSubmit: (String, String, Boolean) -> Unit) {
    var email by remember { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    // Default OFF: registering as a watch (keyDeviceSubtype="WATCH") makes the vehicle treat the key
    // as a watch, which does NOT get passive/proximity unlock. Off = a normal phone key (proximity works).
    var enrollAsWatch by remember { mutableStateOf(false) }
    Text("Sign in to Rivian to enroll “$watchName” as a phone key.")
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                )
            }
        },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = enrollAsWatch, onCheckedChange = { enrollAsWatch = it })
        Text("Enroll as watch key (disables proximity unlock)")
    }
    Button(
        onClick = { onSubmit(email.trim(), password, enrollAsWatch) },
        enabled = email.isNotBlank() && password.isNotBlank(),
    ) { Text("Sign in") }
}

@Composable
private fun OtpForm(email: String, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Text("Enter the verification code sent for $email.")
    OutlinedTextField(
        value = code,
        // The Rivian MFA code is numeric — show a digit keypad and keep only digits.
        onValueChange = { entered -> code = entered.filter { it.isDigit() } },
        label = { Text("Code") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (code.isNotBlank()) onSubmit(code) }),
    )
    Button(onClick = { onSubmit(code.trim()) }, enabled = code.isNotBlank()) { Text("Verify") }
}
