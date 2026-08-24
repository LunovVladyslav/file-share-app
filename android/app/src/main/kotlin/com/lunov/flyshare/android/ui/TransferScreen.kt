package com.lunov.flyshare.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunov.flyshare.android.LocalPalette
import com.lunov.flyshare.android.R
import com.lunov.flyshare.android.Type
import com.lunov.flyshare.core.FileProgress
import com.lunov.flyshare.core.FileState
import com.lunov.flyshare.core.IncomingUi
import com.lunov.flyshare.core.OutgoingUi
import com.lunov.flyshare.core.SendStatus
import com.lunov.flyshare.core.TransferStatus

/**
 * One transfer, file by file.
 *
 * The home screen answers "is anything happening". This answers "what
 * exactly": which files have arrived, which is moving right now, and which are
 * still queued — and, for a file that has landed, a way to open it without
 * going hunting through a file manager.
 */
@Composable
fun TransferScreen(
    incoming: IncomingUi,
    outgoing: OutgoingUi,
    canPause: Boolean,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
    onOpenFile: (String?) -> Unit,
    canOpen: (String?) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val view = describe(incoming, outgoing) ?: run {
        Column(modifier.padding(horizontal = 20.dp).padding(top = 12.dp)) {
            TopBar(stringResource(R.string.transfer), onBack)
            EmptyNote(stringResource(R.string.nothing_in_progress))
        }
        return
    }

    Column(modifier.padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 20.dp)) {
        TopBar(view.title, onBack)

        OutlinedCard(Modifier.padding(top = 6.dp)) {
            Column(Modifier.padding(15.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.file_count, view.fileCount, view.fileCount) +
                            " · " + stringResource(
                                R.string.progress_of,
                                formatBytes(view.done),
                                formatBytes(view.total),
                            ),
                        style = Type.meta,
                        color = palette.ink3,
                        modifier = Modifier.weight(1f),
                    )
                    Text(view.status, style = Type.status, color = view.tone)
                }

                if (view.running) {
                    Track(view.fraction, Modifier.padding(top = 12.dp), height = 6.dp)
                    Timing(
                        startedAt = view.startedAt,
                        remainingSeconds = estimate(view.startedAt, view.done, view.total),
                        ticking = !view.paused,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                view.detail?.let {
                    Text(it, style = Type.body, color = palette.err, modifier = Modifier.padding(top = 10.dp))
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (view.running && view.outgoing) {
                        if (view.paused) {
                            QuietButton(stringResource(R.string.resume), onResume)
                        } else if (canPause) {
                            QuietButton(stringResource(R.string.pause), onPause)
                        }
                    }
                    if (view.running) {
                        Box(Modifier.padding(start = 8.dp)) {
                            QuietButton(stringResource(R.string.cancel), onCancel)
                        }
                    } else {
                        QuietButton(stringResource(R.string.dismiss)) { onDismiss(); onBack() }
                    }
                }
            }
        }

        SectionLabel(stringResource(R.string.files_section), Modifier.padding(top = 24.dp, bottom = 12.dp))

        if (view.files.isEmpty()) {
            EmptyNote(stringResource(R.string.nothing_in_progress))
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(view.files, key = { it.rel }) { file ->
                FileRow(
                    file = file,
                    openable = !view.outgoing && file.state == FileState.Done && canOpen(file.location),
                    onOpen = { onOpenFile(file.location) },
                )
            }
        }
    }
}

/**
 * One file. The state is a glyph rather than a word: the list can be a
 * thousand rows long, and at that length a column of identical words is
 * noise while a column of shapes is scannable.
 */
@Composable
private fun FileRow(file: FileProgress, openable: Boolean, onOpen: () -> Unit) {
    val palette = LocalPalette.current

    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (file.state) {
                FileState.Done -> Icon(Glyph.Check, palette.ok, size = 16.dp)
                FileState.Moving -> Dot(palette.signal, size = 8.dp)
                FileState.Waiting -> Dot(palette.line, size = 8.dp)
            }
        }

        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                file.rel.substringAfterLast('/'),
                style = Type.body.copy(fontSize = 13.5.sp),
                color = if (file.state == FileState.Waiting) palette.ink3 else palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.state == FileState.Moving) {
                Track(file.fraction, Modifier.padding(top = 6.dp), height = 3.dp)
            } else {
                Text(
                    formatBytes(file.size),
                    style = Type.meta,
                    color = palette.ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (openable) {
            Box(Modifier.padding(start = 8.dp)) {
                IconButton(Glyph.Open, stringResource(R.string.open_file), onOpen)
            }
        }
    }
}

/** Everything the screen needs, whichever direction the transfer is going. */
private data class View(
    val title: String,
    val status: String,
    val tone: androidx.compose.ui.graphics.Color,
    val fileCount: Int,
    val done: Long,
    val total: Long,
    val fraction: Float,
    val startedAt: Long,
    val files: List<FileProgress>,
    val running: Boolean,
    val paused: Boolean,
    val outgoing: Boolean,
    val detail: String?,
)

@Composable
private fun describe(incoming: IncomingUi, outgoing: OutgoingUi): View? {
    val palette = LocalPalette.current

    when (incoming) {
        is IncomingUi.Busy, is IncomingUi.Finished -> {
            val p = when (incoming) {
                is IncomingUi.Busy -> incoming.progress
                is IncomingUi.Finished -> incoming.progress
                else -> return null
            }
            val running = p.status == TransferStatus.Receiving
            return View(
                title = stringResource(
                    if (running) R.string.receiving_from else R.string.received_from,
                    p.peerName,
                ),
                status = when (p.status) {
                    TransferStatus.Complete -> stringResource(R.string.status_done)
                    TransferStatus.Receiving -> stringResource(R.string.status_receiving)
                    TransferStatus.Declined -> stringResource(R.string.declined)
                    else -> stringResource(R.string.status_failed)
                },
                tone = when (p.status) {
                    TransferStatus.Complete -> palette.ok
                    TransferStatus.Failed -> palette.err
                    else -> palette.ink3
                },
                fileCount = p.fileCount,
                done = p.received,
                total = p.totalSize,
                fraction = p.fraction,
                startedAt = p.startedAt,
                files = p.files,
                running = running,
                paused = false,
                outgoing = false,
                detail = p.detail,
            )
        }
        else -> {}
    }

    val p = when (outgoing) {
        is OutgoingUi.Busy -> outgoing.progress
        is OutgoingUi.Finished -> outgoing.progress
        OutgoingUi.None -> return null
    }
    val running = p.status == SendStatus.Sending ||
        p.status == SendStatus.Paused ||
        p.status == SendStatus.Connecting ||
        p.status == SendStatus.Waiting

    return View(
        title = stringResource(
            if (p.status == SendStatus.Complete) R.string.sent_to else R.string.sending_to,
            p.peerName,
        ),
        status = when (p.status) {
            SendStatus.Complete -> stringResource(R.string.status_done)
            SendStatus.Paused -> stringResource(R.string.status_paused)
            SendStatus.Sending -> stringResource(R.string.status_sending)
            SendStatus.Failed, SendStatus.Declined -> stringResource(R.string.status_failed)
            else -> stringResource(R.string.status_waiting)
        },
        tone = when (p.status) {
            SendStatus.Complete -> palette.ok
            SendStatus.Failed, SendStatus.Declined -> palette.err
            SendStatus.Paused -> palette.signal
            else -> palette.ink3
        },
        fileCount = p.fileCount,
        done = p.sent,
        total = p.totalSize,
        fraction = p.fraction,
        startedAt = p.startedAt,
        files = p.files,
        running = running,
        paused = p.status == SendStatus.Paused,
        outgoing = true,
        detail = p.detail,
    )
}
