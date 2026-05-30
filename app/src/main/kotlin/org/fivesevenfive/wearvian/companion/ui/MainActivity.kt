package org.fivesevenfive.wearvian.companion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.fivesevenfive.wearvian.companion.ui.EnrollViewModel.UiState

class MainActivity : ComponentActivity() {

    private val vm: EnrollViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by vm.state.collectAsStateWithLifecycle()
                    EnrollScreen(
                        state = state,
                        onCredentials = vm::submitCredentials,
                        onOtp = vm::submitOtp,
                        onReset = vm::reset,
                    )
                }
            }
        }
    }
}

@Composable
private fun EnrollScreen(
    state: UiState,
    onCredentials: (String, String) -> Unit,
    onOtp: (String) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("wearvian companion", style = MaterialTheme.typography.headlineSmall)
        when (state) {
            is UiState.Waiting -> Text(
                "Waiting for your watch. Open wearvian on your Pixel Watch and tap " +
                    "“Enroll” to begin.",
            )

            is UiState.NeedCredentials -> CredentialsForm(state.watchName, onCredentials)

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
    }
}

@Composable
private fun CredentialsForm(watchName: String, onSubmit: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text("Sign in to Rivian to enroll “$watchName” as a phone key.")
    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") })
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        visualTransformation = PasswordVisualTransformation(),
    )
    Button(
        onClick = { onSubmit(email.trim(), password) },
        enabled = email.isNotBlank() && password.isNotBlank(),
    ) { Text("Sign in") }
}

@Composable
private fun OtpForm(email: String, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Text("Enter the verification code sent for $email.")
    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Code") })
    Button(onClick = { onSubmit(code.trim()) }, enabled = code.isNotBlank()) { Text("Verify") }
}
