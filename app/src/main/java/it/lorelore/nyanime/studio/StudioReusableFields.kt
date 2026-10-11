package it.lorelore.nyanime.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun StudioReusableFields(source: StudioSource, node: StudioNode, parameter: (String, String) -> Unit) {
    val cases = runCatching { StudioCalls.cases(node) }.getOrDefault(emptyMap())
    val routing = node.parameters["cases"]?.isNotBlank() == true
    fun changeCases(next: Map<String, String>) {
        parameter(
            "cases",
            if (next.isEmpty()) {
                ""
            } else {
                JsonObject(
                    next.mapValues {
                        JsonPrimitive(it.value)
                    },
                ).toString()
            },
        )
    }
    Row {
        Checkbox(StudioCalls.repeated(node), { parameter("each", it.toString()) })
        Text(stringResource(R.string.studio_call_each), Modifier.padding(top = 12.dp))
    }
    Text(
        stringResource(R.string.studio_call_budget),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row {
        Checkbox(routing, { enabled ->
            if (enabled) {
                parameter("branchField", "kind")
                changeCases(mapOf("value" to source.functions.firstOrNull()?.id.orEmpty()))
            } else {
                parameter("cases", "")
            }
        })
        Text(stringResource(R.string.studio_call_routes), Modifier.padding(top = 12.dp))
    }
    if (routing) {
        StudioField(stringResource(R.string.studio_call_field), node.parameters["branchField"].orEmpty(), {
            parameter("branchField", it)
        })
        cases.entries.forEach { (value, function) ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioField(stringResource(R.string.studio_call_when), value, { next ->
                        if (next == value || next !in cases) changeCases(cases - value + (next to function))
                    })
                    StudioFunctionPicker(source.functions, function, false) { changeCases(cases + (value to it)) }
                    TextButton({ changeCases(cases - value) }) { Text(stringResource(R.string.studio_call_remove)) }
                }
            }
        }
        TextButton({
            val name = generateSequence(1) { it + 1 }.map { "value$it" }.first { it !in cases }
            changeCases(cases + (name to source.functions.firstOrNull()?.id.orEmpty()))
        }, enabled = cases.size < 16 && source.functions.isNotEmpty()) {
            Text(stringResource(R.string.studio_call_add))
        }
        Text(stringResource(R.string.studio_call_otherwise), style = MaterialTheme.typography.labelLarge)
    }
    StudioFunctionPicker(source.functions, node.parameters["function"].orEmpty(), routing) { parameter("function", it) }
}

@Composable
private fun StudioFunctionPicker(
    functions: List<StudioFunction>,
    selected: String,
    optional: Boolean,
    choose: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth(), enabled = functions.isNotEmpty()) {
            Text(
                functions.singleOrNull { it.id == selected }?.name ?: stringResource(
                    if (optional) R.string.studio_call_no_match else R.string.studio_reusable,
                ),
            )
        }
        DropdownMenu(expanded, { expanded = false }) {
            if (optional) {
                DropdownMenuItem(text = { Text(stringResource(R.string.studio_call_no_match)) }, onClick = {
                    choose("")
                    expanded = false
                })
            }
            functions.forEach { function ->
                DropdownMenuItem(text = { Text(function.name) }, onClick = {
                    choose(function.id)
                    expanded = false
                })
            }
        }
    }
}
