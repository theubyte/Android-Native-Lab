package de.millione.schulranzen.rechnen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.math.MathEngine
import de.millione.schulranzen.core.math.MathOutcome
import de.millione.schulranzen.core.shk.Exercise
import de.millione.schulranzen.core.shk.FormulaExercises
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.SignalButton

/**
 * Practice for one formula: a task with random values, an answer field that
 * also takes calculations, and the worked solution on request. Nothing is
 * stored; a new task is one tap away, which is what exam practice needs.
 */
@Composable
internal fun ExercisePanel(formulaId: String, modifier: Modifier = Modifier) {
    val c = Ranzen.colors
    var exercise by remember(formulaId) { mutableStateOf<Exercise?>(null) }
    var input by remember(formulaId) { mutableStateOf("") }
    var verdict by remember(formulaId) { mutableStateOf<Boolean?>(null) }
    var showWay by remember(formulaId) { mutableStateOf(false) }

    fun fresh() {
        exercise = FormulaExercises.generate(formulaId)
        input = ""; verdict = null; showWay = false
    }

    val task = exercise
    if (task == null) {
        if (formulaId in FormulaExercises.formulaIds) QuietButton("Übungsaufgabe stellen", { fresh() }, modifier = modifier, color = c.signal)
        return
    }
    Column(modifier.fillMaxWidth().recessed(18.dp).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Caption("Übungsaufgabe")
        Spacer(Modifier.height(6.dp))
        Text(task.question, style = Ranzen.type.body, color = c.ink)
        Spacer(Modifier.height(12.dp))
        val parsed = (MathEngine.tryEvaluate(input) as? MathOutcome.Ok)?.result?.value
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberField(
                input, { input = it; verdict = null }, task.unit, Modifier.width(240.dp),
                placeholder = "Ergebnis", error = input.isNotBlank() && parsed == null,
            )
            Spacer(Modifier.width(12.dp))
            SignalButton("Prüfen", { verdict = parsed?.let(task::accepts) })
        }
        verdict?.let { right ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lamp(c.signal, size = 8.dp, glow = right)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (right) "Richtig: ${task.answerText}" else "Noch nicht richtig. Rechne nach oder sieh dir den Rechenweg an.",
                    style = Ranzen.type.label, color = c.ink,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton(if (showWay) "Rechenweg ausblenden" else "Rechenweg zeigen", { showWay = !showWay }, color = c.signal)
            QuietButton("Neue Aufgabe", { fresh() })
        }
        if (showWay) {
            Spacer(Modifier.height(8.dp))
            task.steps.forEach { step ->
                Text(step, style = Ranzen.type.mono, color = c.ink, modifier = Modifier.padding(vertical = 3.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text("Rundung bis 1 % zählt als richtig.", style = Ranzen.type.caption, color = c.inkMuted)
        }
    }
}
