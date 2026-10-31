package dev.ggoggam.hangar.app.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.session.rememberSignInSheet
import org.jetbrains.compose.resources.stringResource

/** Signing in, or with [onCancel], signing in to another account alongside the current one. */
@Composable
fun LoginScreen(
    vm: LoginViewModel,
    onCancel: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sheet = rememberSignInSheet()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val adding = onCancel != null
            Text(
                stringResource(if (adding) Res.string.add_account_title else Res.string.app_name),
                // The wordmark, or a heading that fits a phone's width.
                style = if (adding) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayLarge,
            )
            Text(
                stringResource(if (adding) Res.string.add_account_tagline else Res.string.login_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            val pending = state.pending
            when {
                state.tokenEntry -> {
                    TokenEntry(state, onSubmit = vm::useToken, onBack = { vm.showTokenEntry(false) })
                }

                pending == null -> {
                    Button(onClick = { vm.start(sheet::open) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.login_sign_in))
                    }
                    TextButton(onClick = { vm.showTokenEntry(true) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.login_use_token))
                    }
                    TextButton(onClick = vm::tryDemo, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.login_demo))
                    }
                }

                else -> {
                    CodeEntry(
                        state,
                        onSubmit = vm::redeem,
                        onReopen = { sheet.open(pending.approvalUrl) },
                        onStartOver = vm::startOver,
                    )
                }
            }

            if (onCancel != null) {
                TextButton(
                    onClick = {
                        vm.startOver()
                        onCancel()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(Res.string.cancel)) }
            }

            if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            // Code and token problems show under their field, where the keyboard doesn't cover them.
            if (pending == null && !state.tokenEntry) state.problem?.let { Problem(it) }
        }
    }
}

@Composable
private fun CodeEntry(
    state: LoginState,
    onSubmit: (String) -> Unit,
    onReopen: () -> Unit,
    onStartOver: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    Text(stringResource(Res.string.login_waiting), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text(stringResource(Res.string.login_code_label)) },
        isError = state.problem != null,
        supportingText = state.problem?.let { { Problem(it) } },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go,
            ),
        keyboardActions = KeyboardActions(onGo = { onSubmit(code) }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onSubmit(code) }, enabled = !state.busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.login_continue))
    }
    TextButton(onClick = onReopen, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.login_reopen)) }
    TextButton(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.login_restart)) }
}

@Composable
private fun TokenEntry(
    state: LoginState,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
) {
    var token by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Text(stringResource(Res.string.login_token_hint), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text(stringResource(Res.string.login_token_label)) },
        isError = state.problem != null,
        supportingText = state.problem?.let { { Problem(it) } },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onSubmit(token) }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { onSubmit(token) }, enabled = !state.busy && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.login_continue))
    }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.login_token_back)) }
}

@Composable
private fun Problem(problem: LoginProblem) {
    val text =
        when (problem) {
            LoginProblem.Expired -> Res.string.error_expired
            LoginProblem.BadCode -> Res.string.error_bad_code
            LoginProblem.BadToken -> Res.string.error_bad_token
            LoginProblem.Unavailable -> Res.string.error_unavailable
            LoginProblem.Network -> Res.string.error_network
        }
    Text(stringResource(text), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}
