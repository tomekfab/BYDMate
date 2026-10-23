package com.bydmate.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.ui.components.AppAlertDialog
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.NavyDeep
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import com.bydmate.app.voice.AliasSaveResult
import com.bydmate.app.voice.VoiceAlias
import com.bydmate.app.voice.VoiceUserPhrases
import kotlinx.coroutines.launch

/** "Own phrases": the typed alias list (car commands, ranged values, destinations, retained unavailable
 *  records) with a draft editor. Nothing is stored until the user presses Save; delete asks first. The
 *  assistant's suggestions are not shown here: [AliasProposalHost] shows them on every screen. */
@Composable
fun VoiceUserPhrasesScreen(
    onBack: () -> Unit,
    viewModel: VoiceUserPhrasesViewModel = hiltViewModel(),
) {
    val aliases by viewModel.aliases.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val deleteError by viewModel.deleteError.collectAsStateWithLifecycle()
    var runFeedback by remember { mutableStateOf<RunFeedback?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .border(1.5.dp, TextMuted, CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("‹", color = TextSecondary, fontSize = 16.sp)
            }
            Text(
                stringResource(R.string.settings_voice_user_phrases_title),
                color = AccentGreen,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 14.dp).weight(1f)
            )
            TextButton(onClick = { viewModel.startNew() }) {
                Text(stringResource(R.string.voice_alias_add_button), color = AccentGreen)
            }
        }
        Text(
            stringResource(R.string.voice_user_phrases_hint),
            color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        runFeedback?.let { feedback ->
            Text(
                when (feedback) {
                    is RunFeedback.Blocked -> feedback.text
                    RunFeedback.Refused -> stringResource(R.string.voice_alias_run_refused)
                },
                color = AccentOrange, fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (aliases.isEmpty()) {
            Text(stringResource(R.string.voice_alias_empty), color = TextMuted, fontSize = 13.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(aliases.size, key = { it }) { index ->
                val alias = aliases[index]
                AliasRow(
                    alias = alias,
                    description = viewModel.describe(alias),
                    runnable = remember(alias) { viewModel.isRunnable(alias) },
                    onRun = {
                        scope.launch {
                            runFeedback = viewModel.runFeedback(viewModel.run(alias))
                        }
                    },
                    onEdit = { runFeedback = null; viewModel.startEdit(alias) },
                    onDelete = { viewModel.requestDelete(alias) },
                )
            }
        }
    }

    draft?.let { current ->
        AliasDraftDialog(viewModel = viewModel, draft = current)
    }
    pendingDelete?.let { alias ->
        AppAlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            containerColor = CardSurfaceElevated,
            title = { Text(stringResource(R.string.voice_alias_delete_title), color = TextPrimary) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.voice_alias_delete_message, alias.phrase),
                        color = TextSecondary, fontSize = 14.sp,
                    )
                    deleteError?.let { IssueText(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text(stringResource(R.string.voice_alias_delete_confirm), color = AccentOrange)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) {
                    Text(stringResource(R.string.settings_cancel_button), color = TextSecondary)
                }
            },
        )
    }
}

/**
 * The assistant's alias suggestion as an explicit Save / Dismiss confirmation. Hosted once, app-wide
 * (see `AppNavigation`), so a suggestion is visible wherever the user is, not only inside the editor.
 * It stays until the user decides or a newer suggestion replaces it; nothing is saved or run by itself.
 */
@Composable
fun AliasProposalHost(viewModel: VoiceUserPhrasesViewModel = hiltViewModel()) {
    val proposal by viewModel.proposal.collectAsStateWithLifecycle()
    val current = proposal ?: return
    var issue by remember(current.proposalId) { mutableStateOf<DraftIssue?>(null) }
    val scope = rememberCoroutineScope()
    val warning = remember(current.phrase) { viewModel.warningFor(current.phrase) }
    AppAlertDialog(
        onDismissRequest = { viewModel.dismissProposal(current.proposalId) },
        containerColor = CardSurfaceElevated,
        title = { Text(stringResource(R.string.voice_alias_proposal_title), color = TextPrimary) },
        text = {
            Column {
                Text(
                    stringResource(R.string.voice_alias_proposal_body, current.phrase, current.summary),
                    color = TextSecondary, fontSize = 14.sp,
                )
                warning?.let { WarningText(it) }
                issue?.let { IssueText(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    issue = when (val r = viewModel.saveProposal(current.proposalId)) {
                        AliasSaveResult.Saved, AliasSaveResult.NotPending -> null
                        AliasSaveResult.Unavailable -> null
                        is AliasSaveResult.Rejected -> VoiceUserPhrasesViewModel.issueOf(r.check)
                        AliasSaveResult.PersistFailed -> DraftIssue.SaveFailed
                    }
                }
            }) {
                Text(stringResource(R.string.voice_alias_proposal_save), color = AccentGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismissProposal(current.proposalId) }) {
                Text(stringResource(R.string.voice_alias_proposal_dismiss), color = TextSecondary)
            }
        },
    )
}

@Composable
private fun AliasRow(
    alias: VoiceAlias,
    description: String,
    runnable: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "«${alias.phrase}»",
                        color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                    Text(description, color = TextSecondary, fontSize = 13.sp)
                }
                if (runnable) {
                    TextButton(onClick = onRun) {
                        Text(stringResource(R.string.voice_alias_run_button), color = AccentGreen)
                    }
                }
                TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.voice_alias_edit_button), color = AccentGreen)
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.places_delete_content_description),
                        tint = TextMuted,
                    )
                }
            }
            if (!alias.executable) {
                Text(
                    stringResource(R.string.voice_alias_unavailable_badge),
                    color = AccentOrange, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.voice_alias_unavailable_note),
                    color = TextMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AliasDraftDialog(viewModel: VoiceUserPhrasesViewModel, draft: AliasDraft) {
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val selected = viewModel.commands.firstOrNull { it.id == draft.commandId }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = AccentGreen,
        unfocusedBorderColor = CardBorder,
        focusedLabelColor = AccentGreen,
        unfocusedLabelColor = TextSecondary,
        cursorColor = AccentGreen,
    )
    AppAlertDialog(
        onDismissRequest = { viewModel.cancelDraft() },
        containerColor = CardSurfaceElevated,
        title = {
            Text(
                stringResource(
                    if (draft.editing == null) R.string.voice_alias_add_button else R.string.voice_alias_edit_button,
                ),
                color = TextPrimary,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = draft.phrase,
                    onValueChange = { text -> viewModel.updateDraft { it.copy(phrase = text.take(VoiceUserPhrases.MAX_CHARS)) } },
                    label = { Text(stringResource(R.string.voice_user_phrases_field)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors,
                )
                Row(modifier = Modifier.padding(top = 6.dp)) {
                    KindChoice(R.string.voice_alias_kind_vehicle, draft.kind == DraftKind.VEHICLE) {
                        viewModel.updateDraft { it.copy(kind = DraftKind.VEHICLE) }
                    }
                    KindChoice(R.string.voice_alias_kind_destination, draft.kind == DraftKind.DESTINATION) {
                        viewModel.updateDraft { it.copy(kind = DraftKind.DESTINATION) }
                    }
                }
                if (draft.kind == DraftKind.VEHICLE) {
                    Box {
                        TextButton(onClick = { menuOpen = true }) {
                            Text(
                                selected?.labelPl ?: stringResource(R.string.voice_alias_action_field),
                                color = if (selected == null) TextSecondary else AccentGreen,
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            viewModel.commands.forEach { cmd ->
                                DropdownMenuItem(
                                    text = { Text(cmd.labelPl) },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.updateDraft { it.copy(commandId = cmd.id, valueText = "") }
                                    },
                                )
                            }
                        }
                    }
                    selected?.range?.let { range ->
                        OutlinedTextField(
                            value = draft.valueText,
                            onValueChange = { text -> viewModel.updateDraft { it.copy(valueText = VoiceUserPhrasesViewModel.sanitizeValue(text, range)) } },
                            label = { Text(stringResource(R.string.voice_alias_value_field, range.first, range.last)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = fieldColors,
                        )
                    }
                } else {
                    Row {
                        KindChoice(R.string.voice_alias_destination_home, draft.shortcut == "home") {
                            viewModel.updateDraft { it.copy(shortcut = "home") }
                        }
                        KindChoice(R.string.voice_alias_destination_work, draft.shortcut == "work") {
                            viewModel.updateDraft { it.copy(shortcut = "work") }
                        }
                        KindChoice(R.string.voice_alias_destination_place, draft.shortcut == null) {
                            viewModel.updateDraft { it.copy(shortcut = null) }
                        }
                    }
                    if (draft.shortcut == null) {
                        OutlinedTextField(
                            value = draft.query,
                            onValueChange = { text -> viewModel.updateDraft { it.copy(query = text) } },
                            label = { Text(stringResource(R.string.voice_alias_destination_place)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = fieldColors,
                        )
                    }
                }
                draft.editing?.takeIf { !it.executable }?.let {
                    Text(
                        stringResource(R.string.voice_alias_unavailable_note),
                        color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                draft.warning?.let { WarningText(it) }
                draft.issue?.let { IssueText(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { viewModel.saveDraft(confirmWarning = draft.warning != null) }
            }) {
                Text(
                    stringResource(
                        if (draft.warning != null) R.string.voice_alias_shadow_save else R.string.automation_save_button,
                    ),
                    color = AccentGreen,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.cancelDraft() }) {
                Text(stringResource(R.string.settings_cancel_button), color = TextSecondary)
            }
        },
    )
}

@Composable
private fun KindChoice(label: Int, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            stringResource(label),
            color = if (selected) AccentGreen else TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun WarningText(warning: DraftWarning) {
    Text(
        stringResource(
            when (warning) {
                DraftWarning.GRAMMAR_SHADOW -> R.string.voice_alias_shadow_warning
                DraftWarning.NEVER_RUNS -> R.string.voice_alias_never_runs_warning
            },
        ),
        color = AccentOrange, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun IssueText(issue: DraftIssue) {
    val text = when (issue) {
        DraftIssue.Empty -> stringResource(R.string.automation_voice_phrase_empty)
        DraftIssue.TooLong -> stringResource(R.string.voice_user_phrases_too_long, VoiceUserPhrases.MAX_CHARS)
        DraftIssue.TooMany -> stringResource(R.string.voice_user_phrases_too_many, VoiceUserPhrases.MAX_PHRASES)
        is DraftIssue.InUse -> stringResource(R.string.automation_voice_phrase_taken, issue.owner)
        DraftIssue.TargetRequired -> stringResource(R.string.voice_alias_target_required)
        DraftIssue.ValueInvalid -> stringResource(R.string.voice_alias_value_invalid)
        DraftIssue.TargetUnavailable -> stringResource(R.string.voice_alias_target_unavailable)
        DraftIssue.SaveFailed -> stringResource(R.string.voice_alias_save_failed)
    }
    Text(text, color = AccentOrange, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
}
