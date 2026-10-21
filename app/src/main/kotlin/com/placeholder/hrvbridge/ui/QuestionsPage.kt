package com.placeholder.hrvbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.placeholder.hrvbridge.R
import com.placeholder.hrvbridge.core.ReadinessAnswers

/**
 * Optional morning questions after a result (Settings, "Morning questions"). Every
 * question is optional; unanswered ones are not sent. Labels are Intervals.icu's own,
 * 1 = best.
 */
@Composable
fun QuestionsPage(submit: (ReadinessAnswers?) -> Unit, modifier: Modifier = Modifier, initial: ReadinessAnswers = ReadinessAnswers()) {
    var soreness by rememberSaveable { mutableStateOf(initial.soreness) }
    var fatigue by rememberSaveable { mutableStateOf(initial.fatigue) }
    var motivation by rememberSaveable { mutableStateOf(initial.motivation) }
    var injury by rememberSaveable { mutableStateOf(initial.injury) }
    var sick by rememberSaveable { mutableStateOf(initial.sick) }
    val lowToExtreme = stringArrayResource(R.array.q_low_to_extreme).toList()

    Column(modifier.fillMaxSize().background(hrv.bg)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.margin),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                StepIndicator(Step.UPLOAD, StepMark.HOLLOW)
            }
            Text(stringResource(R.string.questions_title), style = MaterialTheme.typography.headlineMedium, color = hrv.ink,
                 modifier = Modifier.semantics { heading() })
            PillRow(stringResource(R.string.q_soreness), lowToExtreme, soreness?.minus(1)) { soreness = it?.plus(1) }
            PillRow(stringResource(R.string.q_fatigue), lowToExtreme, fatigue?.minus(1)) { fatigue = it?.plus(1) }
            PillRow(stringResource(R.string.q_motivation), stringArrayResource(R.array.q_motivation_labels).toList(),
                    motivation?.minus(1)) { motivation = it?.plus(1) }
            PillRow(stringResource(R.string.q_injury), stringArrayResource(R.array.q_injury_labels).toList(),
                    injury?.minus(1)) { injury = it?.plus(1) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PillRow(stringResource(R.string.q_sick), stringArrayResource(R.array.q_no_yes).toList(),
                        sick?.let { if (it) 1 else 0 }) { sick = it?.let { i -> i == 1 } }
                Text(stringResource(R.string.q_sick_note), style = MaterialTheme.typography.bodySmall, color = hrv.ink2)
            }
            Text(stringResource(R.string.q_footnote), style = MaterialTheme.typography.bodySmall, color = hrv.ink2,
                 modifier = Modifier.padding(bottom = 16.dp))
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = Space.bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PrimaryKey(stringResource(R.string.q_upload), {
                submit(ReadinessAnswers.of(soreness, fatigue, motivation, injury, sick))
            })
            TextKey(stringResource(R.string.q_skip)) { submit(null) }
        }
    }
}

/** A question with pill options. Tapping the selected pill clears it (every question is optional). */
@Composable
fun PillRow(title: String, labels: List<String>, selected: Int?, select: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = hrv.ink)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, label ->
                val on = selected == i
                val shape = RoundedCornerShape(percent = 50)
                Box(
                    Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                        .background(if (on) hrv.ink else hrv.surface, shape)
                        .border(1.dp, if (on) hrv.ink else hrv.line, shape)
                        .selectable(selected = on, role = Role.RadioButton) { select(if (on) null else i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, textAlign = TextAlign.Center,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) hrv.bg else hrv.ink,
                    )
                }
            }
        }
    }
}
