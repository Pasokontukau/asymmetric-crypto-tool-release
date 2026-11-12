package com.cryptoassistant.app.feature.contacts

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.keys.ContactKey
import com.cryptoassistant.app.data.keys.PrivateKeyBackup
import com.cryptoassistant.app.data.keys.keyErrorFor
import com.cryptoassistant.app.data.keys.keyErrorMessage
import com.cryptoassistant.app.ui.components.AppCard
import com.cryptoassistant.app.ui.theme.Spacing
import kotlinx.coroutines.launch

// Toast 在协程里执行，不能用 stringResource；这里只取 Toast 文案，不参与重组。
@SuppressLint("LocalContextGetResourceValueCall", "LocalContextResourcesRead")
@Composable
fun ContactsScreen(
    contacts: List<ContactKey>,
    hasIdentity: Boolean,
    onAdd: suspend (String, String) -> Result<ContactKey>,
    onRemove: suspend (String) -> Result<Unit>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ContactKey?>(null) }
    val clipboardLabel = stringResource(R.string.contacts_clipboard_label)
    val copiedText = stringResource(R.string.contacts_copied_key)
    val copyDescription = stringResource(R.string.contacts_copy_key)
    val deleteDescription = stringResource(R.string.contacts_delete_contact)

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.screenPadding,
            end = Spacing.screenPadding,
            bottom = Spacing.xxl,
        ),
    ) {
        item {
            Spacer(Modifier.height(Spacing.sm))
            if (!hasIdentity) {
                Text(
                    text = stringResource(R.string.contacts_identity_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.lg))
            }
            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.sm))
                Text(stringResource(R.string.contacts_add_button))
            }
        }

        if (contacts.isEmpty()) {
            item {
                Spacer(Modifier.height(Spacing.md))
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.People,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(Spacing.md))
                        Text(
                            text = stringResource(R.string.contacts_empty_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(R.string.contacts_empty_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(contacts, key = { it.id }) { contact ->
            Spacer(Modifier.height(Spacing.md))
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = contact.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = PrivateKeyBackup.formatFingerprint(contact.fingerprint),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText(clipboardLabel, contact.publicKeyBase64),
                            )
                            Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = copyDescription)
                    }
                    IconButton(onClick = { deleteTarget = contact }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = deleteDescription)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(Spacing.section)) }
    }

    if (showAddDialog) {
        AddContactDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, keyText -> onAdd(name, keyText) },
            onPasted = { showAddDialog = false },
        )
    }

    deleteTarget?.let { target ->
        val fingerprint = PrivateKeyBackup.formatFingerprint(target.fingerprint)
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.contacts_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.contacts_delete_body,
                        target.name,
                        fingerprint,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val removed = target
                        deleteTarget = null
                        scope.launch {
                            onRemove(removed.id).onFailure { error ->
                                Toast.makeText(
                                    context,
                                    keyErrorMessage(context, error),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                ) {
                    Text(
                        stringResource(R.string.contacts_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onConfirm: suspend (String, String) -> Result<ContactKey>,
    onPasted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var keyText by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val addedText = stringResource(R.string.contacts_added)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.contacts_add_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.contacts_name_label)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text(stringResource(R.string.contacts_public_key_label)) },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.Start) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            val pasted = clipboard?.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                            if (pasted.isNullOrBlank()) {
                                errorRes = R.string.contacts_clipboard_empty
                            } else {
                                keyText = pasted.trim()
                                errorRes = null
                            }
                        },
                    ) {
                        Icon(
                            Icons.Outlined.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(Spacing.xs))
                        Text(stringResource(R.string.contacts_paste))
                    }
                }
                errorRes?.let { messageRes ->
                    Text(
                        text = stringResource(messageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.contacts_fingerprint_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank() && keyText.isNotBlank(),
                onClick = {
                    busy = true
                    errorRes = null
                    scope.launch {
                        onConfirm(name.trim(), keyText.trim())
                            .onSuccess {
                                Toast.makeText(context, addedText, Toast.LENGTH_SHORT).show()
                                onPasted()
                            }
                            .onFailure { error ->
                                errorRes = keyErrorFor(error).messageRes
                                busy = false
                            }
                    }
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.contacts_add))
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
