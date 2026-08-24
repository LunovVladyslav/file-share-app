package com.lunov.flyshare.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunov.flyshare.android.LocalPalette
import com.lunov.flyshare.android.R
import com.lunov.flyshare.android.Type
import com.lunov.flyshare.core.HistoryEntry
import com.lunov.flyshare.core.Outcome
import java.text.DateFormat
import java.util.Date

/**
 * What has been sent and received.
 *
 * Summaries, not file lists. A thousand-file transfer would put a megabyte of
 * names into storage for something nobody reads afterwards, and the folder it
 * landed in answers "what did I get" better than a list copied out of it. The
 * per-file view is live while a transfer runs; once it is over, the
 * destination is the record.
 */
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    onBack: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 20.dp)) {
        TopBar(
            title = stringResource(R.string.history),
            onBack = onBack,
            action = {
                if (entries.isNotEmpty()) QuietButton(stringResource(R.string.clear), onClear)
            },
        )

        if (entries.isEmpty()) {
            EmptyNote(stringResource(R.string.history_empty))
            return@Column
        }

        LazyColumn(
            verticalArrangement = RowGap,
            modifier = Modifier.padding(top = 10.dp),
        ) {
            items(entries, key = { it.id }) { entry -> HistoryRow(entry) }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry) {
    val palette = LocalPalette.current

    val (status, tone) = when (entry.outcome) {
        Outcome.Complete -> stringResource(R.string.status_done) to palette.ok
        Outcome.Declined -> stringResource(R.string.declined) to palette.ink3
        Outcome.Failed -> stringResource(R.string.status_failed) to palette.err
    }

    OutlinedCard {
        Column(Modifier.padding(15.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        if (entry.outgoing) R.string.sent_to else R.string.received_from,
                        entry.peerName,
                    ),
                    style = Type.title,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(status, style = Type.status, color = tone, modifier = Modifier.padding(start = 10.dp))
            }

            Text(
                pluralStringResource(R.plurals.file_count, entry.fileCount, entry.fileCount) +
                    " · " + formatBytes(entry.transferred) +
                    (
                        entry.averageRate?.let {
                            " · " + stringResource(
                                R.string.average_rate,
                                stringResource(R.string.per_second, formatBytes(it.toLong())),
                            )
                        } ?: ""
                        ),
                style = Type.meta,
                color = palette.ink3,
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(formatWhen(entry.finishedAt), style = Type.meta, color = palette.ink3)
                entry.destination?.takeIf { !entry.outgoing }?.let {
                    Text(
                        it,
                        style = Type.meta,
                        color = palette.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                }
            }

            entry.detail?.let {
                Text(it, style = Type.meta, color = palette.err, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** The app's language, not the phone's — see formatDate in SettingsScreen. */
@Composable
private fun formatWhen(millis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    if (millis <= 0) return "—"
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(millis))
}
