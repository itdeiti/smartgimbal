package dev.smartgimbal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.smartgimbal.tracking.DEG_PER_SEC_PER_UNIT
import dev.smartgimbal.tracking.TrackingParams
import java.util.Locale
import kotlin.math.roundToInt

/** Live tuning of the face tracker; changes apply to the next frame and are saved. */
@Composable
fun TuningCard(
    params: TrackingParams,
    onChange: ((TrackingParams) -> TrackingParams) -> Unit,
    onReset: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tracking-Tuning", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onReset) { Text("Standardwerte") }
            }
            ParamSlider("Kp", "%.2f /s".fmt(params.kp), params.kp, 0.5f..5f) { v -> onChange { it.copy(kp = v) } }
            ParamSlider("Totzone", "%.1f°".fmt(params.deadzoneDeg), params.deadzoneDeg, 0.5f..10f) { v ->
                onChange { it.copy(deadzoneDeg = v) }
            }
            ParamSlider(
                "Max. Speed",
                "%d (≈ %.0f°/s)".fmt(params.maxUnits, params.maxUnits * DEG_PER_SEC_PER_UNIT),
                params.maxUnits.toFloat(),
                50f..600f,
            ) { v -> onChange { it.copy(maxUnits = v.roundToInt()) } }
            ParamSlider("Glättung", "%.2f".fmt(params.smoothing), params.smoothing, 0.1f..1f) { v ->
                onChange { it.copy(smoothing = v) }
            }
            ParamSlider("Kd", "%.2f".fmt(params.kd), params.kd, 0f..0.5f) { v -> onChange { it.copy(kd = v) } }

            Text("Vorzeichen (Gesicht rechts/oben → Gimbal rechts/hoch)", style = MaterialTheme.typography.labelMedium)
            SignSwitch("Hauptkamera Pan", params.rearPanSign) { s -> onChange { it.copy(rearPanSign = s) } }
            SignSwitch("Hauptkamera Tilt", params.rearTiltSign) { s -> onChange { it.copy(rearTiltSign = s) } }
            SignSwitch("Frontkamera Pan", params.frontPanSign) { s -> onChange { it.copy(frontPanSign = s) } }
            SignSwitch("Frontkamera Tilt", params.frontTiltSign) { s -> onChange { it.copy(frontTiltSign = s) } }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = current.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

/** Switch on = sign +1. */
@Composable
private fun SignSwitch(label: String, sign: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(if (sign >= 0) "+1" else "−1", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
        Switch(checked = sign >= 0, onCheckedChange = { onChange(if (it) 1 else -1) })
    }
}

private fun String.fmt(vararg args: Any): String = format(Locale.GERMANY, *args)
