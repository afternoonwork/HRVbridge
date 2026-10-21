package com.placeholder.hrvbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import com.placeholder.hrvbridge.R

/** What Settings needs from the app; plain callbacks so the screenshot test can show it. */
class SettingsActions(
    val setSettling: (Int) -> Unit = {},
    val questionsEnabled: Boolean = false,
    val enableQuestions: (Boolean) -> Unit = {},
    val disconnect: () -> Unit = {},
    val deleteAll: () -> Unit = {},
    val openDebugLog: () -> Unit = {},
    /** Debug builds only: scenario names, speeds, and connecting the simulated strap. */
    val simScenarios: List<String> = emptyList(),
    val simSpeeds: List<Int> = emptyList(),
    val connectSimulated: (String, Int) -> Unit = { _, _ -> },
)

@Composable
fun SettingsContent(settlingSeconds: Int, actions: SettingsActions, modifier: Modifier = Modifier) {
    var pickSettling by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().background(hrv.bg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Header(stringResource(R.string.settings_measurement))
        Group {
            Row1(stringResource(R.string.settling_time), stringResource(R.string.seconds, settlingSeconds)) { pickSettling = true }
        }
        Footer(stringResource(R.string.settling_footer))
        Group {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    .toggleable(value = actions.questionsEnabled, role = Role.Switch, onValueChange = actions.enableQuestions)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.questions_switch), style = MaterialTheme.typography.bodyLarge, color = hrv.ink,
                     modifier = Modifier.weight(1f))
                Switch(checked = actions.questionsEnabled, onCheckedChange = null)
            }
        }
        Footer(questionsFooter(stringResource(R.string.questions_footer)))

        Header(stringResource(R.string.settings_intervals))
        Group {
            Row1(stringResource(R.string.api_key_label), stringResource(R.string.connected_value))
            Divider()
            Row1(stringResource(R.string.disconnect), color = MaterialTheme.colorScheme.error, onClick = actions.disconnect)
        }

        Header(stringResource(R.string.settings_data))
        Group {
            Row1(stringResource(R.string.delete_all), color = MaterialTheme.colorScheme.error) { confirmDelete = true }
            Divider()
            Row1(stringResource(R.string.debug_log), onClick = actions.openDebugLog)
        }
        if (deleted) Footer(stringResource(R.string.delete_all_done))
        Footer(stringResource(R.string.data_footer))

        if (actions.simScenarios.isNotEmpty()) DebugTools(actions)
    }

    if (pickSettling) {
        AlertDialog(
            onDismissRequest = { pickSettling = false },
            title = { Text(stringResource(R.string.settling_time)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    for (s in listOf(0, 30, 60, 90)) {
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                                .selectable(selected = s == settlingSeconds, role = Role.RadioButton) {
                                    actions.setSettling(s)
                                    pickSettling = false
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = s == settlingSeconds, onClick = null)
                            Text(stringResource(R.string.seconds, s), modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickSettling = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_all_title)) },
            text = { Text(stringResource(R.string.delete_all_body)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.deleteAll()
                    confirmDelete = false
                    deleted = true
                }) { Text(stringResource(R.string.delete_all_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Debug builds only, plain list: simulated strap scenario and speed. */
@Composable
private fun DebugTools(actions: SettingsActions) {
    var selected by rememberSaveable { mutableStateOf(actions.simScenarios.first()) }
    var speed by rememberSaveable { mutableIntStateOf(actions.simSpeeds.firstOrNull() ?: 1) }
    Header(stringResource(R.string.debug_tools))
    Group {
        Column(Modifier.selectableGroup()) {
            for (name in actions.simScenarios) {
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                        .selectable(selected = name == selected, role = Role.RadioButton) { selected = name }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = name == selected, onClick = null)
                    Text(name, color = hrv.ink, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sim_speed), color = hrv.ink)
            for (x in actions.simSpeeds) {
                FilterChip(selected = x == speed, onClick = { speed = x }, label = { Text(stringResource(R.string.sim_speed_value, x)) })
            }
        }
        Divider()
        Row1(stringResource(R.string.sim_start)) { actions.connectSimulated(selected, speed) }
    }
    androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp))
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = hrv.ink2,
         modifier = Modifier.padding(start = 16.dp, top = 16.dp))
}

@Composable
private fun Footer(text: String) = Footer(AnnotatedString(text))

@Composable
private fun Footer(text: AnnotatedString) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = hrv.ink2, modifier = Modifier.padding(horizontal = 16.dp))
}

/** The questions footer resource holds `<b>` tags (CDATA) around the five question names. */
internal fun questionsFooter(html: String): AnnotatedString = AnnotatedString.fromHtml(html)

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(hrv.surface, RoundedCornerShape(12.dp))
            .border(1.dp, hrv.line, RoundedCornerShape(12.dp)),
        content = content,
    )
}

@Composable
private fun Divider() = HorizontalDivider(color = hrv.line, modifier = Modifier.padding(start = 16.dp))

@Composable
private fun Row1(title: String, value: String? = null, color: Color = Color.Unspecified, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (color == Color.Unspecified) hrv.ink else color,
             modifier = Modifier.weight(1f))
        value?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = hrv.ink2) }
    }
}
