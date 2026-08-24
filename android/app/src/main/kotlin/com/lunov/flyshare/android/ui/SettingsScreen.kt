package com.lunov.flyshare.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunov.flyshare.android.Language
import com.lunov.flyshare.android.LocalPalette
import com.lunov.flyshare.android.R
import com.lunov.flyshare.android.Radius
import com.lunov.flyshare.android.ThemeChoice
import com.lunov.flyshare.android.Type
import com.lunov.flyshare.android.hairline
import com.lunov.flyshare.core.PairedPeer
import java.text.DateFormat
import java.util.Date

/**
 * Everything that is a preference rather than an action.
 *
 * Where files land used to sit on the home screen, where it competed with the
 * device list for the top of the page. It belongs here, next to the other two
 * things about this device: what it is called, and who it trusts.
 */
@Composable
fun SettingsScreen(
    deviceName: String,
    isCustomName: Boolean,
    destination: String,
    paired: List<PairedPeer>,
    isReachable: (String) -> Boolean,
    theme: ThemeChoice,
    language: Language,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onChangeFolder: () -> Unit,
    onForget: (PairedPeer) -> Unit,
    onTheme: (ThemeChoice) -> Unit,
    onLanguage: (Language) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    var renaming by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<PairedPeer?>(null) }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 32.dp),
    ) {
        TopBar(stringResource(R.string.settings), onBack)

        SectionLabel(stringResource(R.string.this_device), Modifier.padding(top = 14.dp, bottom = 4.dp))
        SettingRow(
            glyph = Glyph.Device,
            label = stringResource(R.string.device_name),
            value = deviceName,
            onClick = { renaming = true },
        )
        SettingRow(
            glyph = Glyph.Folder,
            label = stringResource(R.string.download_folder),
            value = destination,
            onClick = onChangeFolder,
        )

        SectionLabel(stringResource(R.string.devices_section), Modifier.padding(top = 26.dp, bottom = 4.dp))
        if (paired.isEmpty()) {
            Text(
                stringResource(R.string.no_paired_devices),
                style = Type.body,
                color = palette.ink3,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            // Which of them is actually here matters more than any of the
            // rest of this. Three entries called "MSI" is what a laptop that
            // has been reinstalled a few times leaves behind, and without
            // this there is no way to tell which one to keep.
            paired.forEach { peer ->
                val here = isReachable(peer.id)
                SettingRow(
                    glyph = Glyph.Device,
                    label = peer.name,
                    value = if (here) {
                        stringResource(R.string.online_now)
                    } else {
                        "${peer.os} · " + stringResource(R.string.paired_on, formatDate(peer.pairedAt))
                    },
                    leading = { if (here) Dot(palette.ok) },
                    trailing = { QuietButton(stringResource(R.string.forget)) { forgetting = peer } },
                )
            }
        }

        SectionLabel(stringResource(R.string.appearance), Modifier.padding(top = 26.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoice.entries.forEach { choice ->
                Chip(
                    label = stringResource(
                        when (choice) {
                            ThemeChoice.System -> R.string.theme_system
                            ThemeChoice.Light -> R.string.theme_light
                            ThemeChoice.Dark -> R.string.theme_dark
                        },
                    ),
                    selected = choice == theme,
                    modifier = Modifier.weight(1f),
                ) { onTheme(choice) }
            }
        }

        SectionLabel(stringResource(R.string.language), Modifier.padding(top = 26.dp, bottom = 10.dp))
        Language.entries.forEach { choice ->
            // Each language is written in itself: someone looking for their own
            // will not find it listed in one they cannot read.
            Chip(
                label = if (choice == Language.System) stringResource(R.string.theme_system) else choice.label,
                selected = choice == language,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) { onLanguage(choice) }
        }
    }

    if (renaming) {
        RenameDialog(
            current = deviceName,
            canReset = isCustomName,
            onDismiss = { renaming = false },
            onSave = { onRename(it); renaming = false },
        )
    }

    forgetting?.let { peer ->
        ConfirmDialog(
            question = stringResource(R.string.forget_question, peer.name),
            confirm = stringResource(R.string.forget),
            onDismiss = { forgetting = null },
            onConfirm = { onForget(peer); forgetting = null },
        )
    }
}

@Composable
private fun RenameDialog(
    current: String,
    canReset: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val palette = LocalPalette.current
    var text by remember { mutableStateOf(current) }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_name), style = Type.display) },
        text = {
            Column {
                Text(stringResource(R.string.device_name_note), style = Type.meta, color = palette.ink3)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(40) },
                    singleLine = true,
                    textStyle = TextStyle(color = palette.ink, fontSize = Type.body.fontSize),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.signal),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { onSave(text) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(Radius.control))
                        .hairline(palette.line, Radius.control)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
                if (canReset) {
                    // Clearing the field is the way back to the phone's own
                    // name, and nobody would guess that; say it.
                    QuietButton(stringResource(R.string.reset)) { onSave("") }
                }
            }
        },
        confirmButton = { PrimaryButton(stringResource(R.string.save)) { onSave(text) } },
        dismissButton = { QuietButton(stringResource(R.string.cancel), onDismiss) },
    )
}

@Composable
private fun ConfirmDialog(
    question: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(confirm, style = Type.display) },
        text = { Text(question, style = Type.body, color = LocalPalette.current.ink2) },
        confirmButton = { PrimaryButton(confirm, onConfirm) },
        dismissButton = { QuietButton(stringResource(R.string.cancel), onDismiss) },
    )
}

/**
 * DateFormat reads the JVM's default locale, which the in-app language switch
 * never touches — so a Polish screen was printing Ukrainian month names. The
 * locale has to come from the composition, like every other string here.
 */
@Composable
private fun formatDate(millis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    if (millis <= 0) return "—"
    return DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(millis))
}
