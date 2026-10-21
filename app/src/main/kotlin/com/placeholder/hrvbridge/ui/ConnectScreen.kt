package com.placeholder.hrvbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.placeholder.hrvbridge.R

/** The connect screen's state; a plain value so the screenshot test can show each. */
sealed interface ConnectPhase {
    data object Idle : ConnectPhase
    data object Checking : ConnectPhase
    /** Key rejected or could not be stored: the field's error state. */
    data class Problem(val text: Txt) : ConnectPhase
    /** [checked] false: Intervals.icu could not be reached; the key is saved anyway. */
    data class Connected(val checked: Boolean) : ConnectPhase
}

@Composable
fun ConnectScreen(key: String, onKeyChange: (String) -> Unit, phase: ConnectPhase, save: () -> Unit, proceed: () -> Unit) {
    Column(Modifier.fillMaxSize().background(hrv.bg).imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.margin),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.connect_title),
                style = MaterialTheme.typography.headlineMedium, color = hrv.ink,
                modifier = Modifier.padding(top = 32.dp).semantics { heading() },
            )
            if (phase is ConnectPhase.Connected) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(hrv.surface, RoundedCornerShape(14.dp))
                        .border(1.dp, hrv.line, RoundedCornerShape(14.dp))
                        .padding(16.dp)
                        .semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("✓ " + stringResource(R.string.connected_title), style = MaterialTheme.typography.titleMedium, color = hrv.ink)
                    Text(
                        stringResource(if (phase.checked) R.string.key_accepted else R.string.key_unchecked),
                        style = MaterialTheme.typography.bodyMedium, color = hrv.ink2,
                    )
                }
            } else {
                Text(stringResource(R.string.api_key_where), style = MaterialTheme.typography.bodyLarge, color = hrv.ink)
                val problem = phase as? ConnectPhase.Problem
                OutlinedTextField(
                    value = key,
                    onValueChange = onKeyChange,
                    label = { Text(stringResource(R.string.api_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (key.isNotBlank()) save() }),
                    isError = problem != null,
                    supportingText = {
                        if (problem != null) {
                            // Warnings carry an icon and words, never colour alone.
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                WarningIcon(hrv.ink, Modifier.size(16.dp))
                                Text(problem.text.resolve())
                            }
                        } else {
                            Text(stringResource(R.string.api_key_stored))
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = hrv.ink, unfocusedBorderColor = hrv.lineStrong,
                        errorBorderColor = hrv.ink, errorLabelColor = hrv.ink, errorSupportingTextColor = hrv.ink,
                        errorCursorColor = hrv.ink, focusedLabelColor = hrv.ink, unfocusedLabelColor = hrv.ink2,
                        cursorColor = hrv.ink, focusedContainerColor = hrv.surface, unfocusedContainerColor = hrv.surface,
                        errorContainerColor = hrv.surface, unfocusedSupportingTextColor = hrv.ink2, focusedSupportingTextColor = hrv.ink2,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Notice(stringResource(R.string.keep_values_body), lead = stringResource(R.string.keep_values_title),
                   modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
        }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = Space.bottom), contentAlignment = Alignment.Center) {
            when (phase) {
                is ConnectPhase.Connected -> PrimaryKey(stringResource(R.string.continue_), proceed)
                ConnectPhase.Checking -> PrimaryKey(stringResource(R.string.api_key_checking), {}, enabled = false, leading = {
                    Spinner(16.dp, hrv.ink2)
                })
                else -> PrimaryKey(stringResource(R.string.api_key_save), save, enabled = key.isNotBlank())
            }
        }
    }
}
