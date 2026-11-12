package com.cryptoassistant.app.feature.guide

/**
 * 工具书英文正文。
 *
 * 只提供文本：章/节 ID、章号、节号与 related 全部沿用 [GuideContent.chapters]，
 * 因此英文不能改变结构或路由。[GuideContentTest] 会校验每个章/节都有对应文本、
 * block 类型与顺序完全一致，并确认英文里没有中文。
 *
 * 键：章按 `id`；节按 `chapterId to sectionId`。
 */
internal object GuideContentEn {

    data class ChapterText(
        val title: String,
        val summary: String,
        val keyPoints: List<String>,
    )

    data class SectionText(
        val title: String,
        val summary: String,
        val lead: String,
        val blocks: List<GuideBlock>,
    )

    val chapters: Map<String, ChapterText> = mapOf(
        "start" to ChapterText(
            title = "Getting started",
            summary = "The first-run order, the three paths on the home screen, and what this tool can and cannot do.",
            keyPoints = listOf(
                "Create the local key and export a backup first, then add contacts.",
                "Text goes through the floating ball; files go through the home screen or system sharing.",
                "Fully offline: it never sends automatically and never pastes into a chat box for you.",
            ),
        ),
        "text" to ChapterText(
            title = "Text encryption and decryption",
            summary = "Two work modes, recipients, decrypted display, and clipboard fallback.",
            keyPoints = listOf(
                "Quick mode needs accessibility; Standard mode does not.",
                "With multiple contacts and no recipient selected, encryption is blocked.",
                "Decrypted plaintext exists only in the input field or capsule and is cleared on close, timeout, or backgrounding.",
            ),
        ),
        "files" to ChapterText(
            title = "File encryption and decryption",
            summary = "Home file entries, system open/share, quick file handling, and save/cleanup.",
            keyPoints = listOf(
                "You can select multiple files or a whole folder recursively.",
                "System entries classify by file name and header; mixed batches let you choose a subset.",
                "Results stay in the app-private directory first and are cleaned about 6 hours after sharing/opening.",
            ),
        ),
        "keys" to ChapterText(
            title = "Keys and contacts",
            summary = "Local key, backup and restore, public-key fingerprints, and common errors.",
            keyPoints = listOf(
                "The private key is wrapped by Android Keystore; daily use needs no password.",
                "Backups are password-protected .txt files; after deleting the key it cannot be recovered.",
                "Contacts store only public keys and fingerprints, never through a server.",
            ),
        ),
        "ball" to ChapterText(
            title = "Floating ball and accessibility",
            summary = "Turning the ball on, work modes, disclosure, tap decisions, and tiles.",
            keyPoints = listOf(
                "The floating ball needs overlay permission; the switch is in Settings.",
                "Quick mode needs accessibility; Standard mode does not.",
                "Accessibility reads the current input field only when the ball is tapped; it does not monitor the screen.",
            ),
        ),
        "security" to ChapterText(
            title = "Security, logs, and privacy",
            summary = "Security lock, app lock, log gestures, and the local privacy boundary.",
            keyPoints = listOf(
                "The security lock protects infrequent sensitive actions and does not change daily encryption.",
                "Logs record diagnostic facts only, never content.",
                "All data stays on the device; cleanup rules and permissions together form the boundary.",
            ),
        ),
        "trouble" to ChapterText(
            title = "Troubleshooting",
            summary = "Ball taps, WeChat/QQ, write-back, system entries, and log feedback.",
            keyPoints = listOf(
                "Check permissions, the service, and the work mode first, then the specific app.",
                "WeChat/QQ not exposing the input field is their behavior; use the clipboard instead.",
                "When reporting, provide logs and version info, never plaintext or keys.",
            ),
        ),
        "appendix" to ChapterText(
            title = "Appendix",
            summary = "Terms, file formats, version and license, and a settings reference.",
            keyPoints = listOf(
                "Terms are explained as this app uses them, not as textbook definitions.",
                "Only .aegix is recognized; legacy .enc and the old internal VOLU are not.",
                "Version, license, and build info are in Settings → About.",
            ),
        ),
    )

    val sections: Map<Pair<String, String>, SectionText> = mapOf(
        ("start" to "first-run") to SectionText(
            title = "First run",
            summary = "Create a key, back it up, add a contact, and turn on the floating ball.",
            lead = "First use takes four steps: create a key, back it up, add a contact, and turn on the floating ball.",
            blocks = listOf(
                GuideBlock.Steps(
                    listOf(
                        "On the home screen, tap “Local key” → “Create key” and wait for the 4096-bit key generation.",
                        "On the keys page, export a .txt backup, set a password, and store it offline.",
                        "On the contacts page, paste the other person’s public key, verify the fingerprint, and save.",
                        "Settings → “Floating ball & accessibility”: grant overlay permission, turn the ball on, and choose Quick or Standard mode.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "You can use the app without the floating ball: “Encrypt files / Decrypt files” on the home screen and the system “Open with / Share” entries can process files. " +
                        "Text encryption and decryption happen only through the floating ball; there is no separate text page inside the app.",
                ),
                GuideBlock.Note(
                    "Back up before use",
                    "Deleting the local key cannot be undone; if the backup password is lost, that backup can no longer be opened.",
                ),
            ),
        ),
        ("start" to "home-paths") to SectionText(
            title = "Home screen and the three paths",
            summary = "What is on the home screen and how to choose between text, files, and system entries.",
            lead = "The home screen shows local status and three entries; pick the path you need.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "“Local key”: view or create an identity, and open the keys page to back up, copy the public key, or delete.",
                        "“Encrypt files / Decrypt files”: select multiple files or a folder recursively and process them as a streaming queue.",
                        "“User guide”: the book you are reading, organized by topic.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "The text path needs the floating ball; the file path does not depend on accessibility; system entries are other apps sharing or opening a file with this app, which also opens the files page. " +
                        "All three paths share the same keys and contacts.",
                ),
                GuideBlock.Note(
                    "When no key exists",
                    "Encrypting files and encrypting text are both blocked. Create the local key first, then add at least one contact.",
                ),
            ),
        ),
        ("start" to "boundaries") to SectionText(
            title = "Scope and limits",
            summary = "What an offline tool can and cannot do.",
            lead = "This is a local, offline tool, not a chat plug-in, and it never sends messages for you.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "No network permission, no cloud, no analytics; keys, plaintext, and ciphertext are processed only on this device.",
                        "Encryption needs the recipient’s public key; decryption only works for ciphertext addressed to the local key.",
                        "It never sends automatically, never pastes automatically, and never taps the send button in a chat window for you.",
                        "Apps such as WeChat and QQ may hide third-party accessibility nodes; when the input field cannot be read, only the clipboard fallback remains.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "The security goal is to reduce plaintext exposure and accidental actions, not to defend against someone who already controls the device. " +
                        "If the device is rooted, has malware, or is unlocked by someone else, the app cannot provide extra protection.",
                ),
                GuideBlock.Note(
                    "Things you maintain yourself",
                    "Backup files and passwords, the source of contact public keys, and the device’s own screen lock and system updates are all your responsibility.",
                ),
            ),
        ),

        ("text" to "text-quick") to SectionText(
            title = "Quick mode",
            summary = "Tap the ball to read and write the current input field in place.",
            lead = "On tap, it reads the current input field directly, encrypts or decrypts, and writes back to the same field.",
            blocks = listOf(
                GuideBlock.Paragraph(
                    "Quick mode needs the accessibility service. It only handles the focused, editable, non-password input field, and it processes the whole field; " +
                        "even if part of the text is selected, it does not replace only half.",
                ),
                GuideBlock.Steps(
                    listOf(
                        "Tap the target input field so the cursor is where you want to process.",
                        "Tap the floating ball: ordinary text is encrypted, ciphertext from this app is decrypted.",
                        "The ciphertext is written back to the field; decrypted plaintext is written back and shown in the stretched capsule.",
                    ),
                ),
                GuideBlock.Note(
                    "Skipped on purpose",
                    "Password fields, PINs, payment and verification-code screens are never read or written; when it cannot read, it only shows a hint and never guesses the content.",
                ),
            ),
        ),
        ("text" to "text-standard") to SectionText(
            title = "Standard mode",
            summary = "Text panel without accessibility.",
            lead = "It does not touch accessibility and does not watch the clipboard in the background; tapping the ball opens the text panel.",
            blocks = listOf(
                GuideBlock.Paragraph(
                    "Paste or type manually in the panel. When the input is empty, the title is “Text encryption”; once there is content it decides the direction: " +
                        "ordinary text is encrypted, ciphertext from this app is decrypted.",
                ),
                GuideBlock.Bullets(
                    listOf(
                        "“Fill from clipboard” reads the clipboard only when you tap it.",
                        "The ciphertext is copied to the clipboard; you paste it into the target yourself.",
                        "Decrypted text is shown in the panel and must be copied manually.",
                        "With multiple contacts and no recipient selected, encryption is blocked.",
                    ),
                ),
                GuideBlock.Note(
                    "Who it is for",
                    "If you do not want to enable accessibility, or the target app does not accept writes, Standard mode is the safest choice. It never reads input fields in other apps.",
                ),
            ),
        ),
        ("text" to "text-recipient") to SectionText(
            title = "Recipients and the long-press menu",
            summary = "Who to encrypt to, and behavior with one or multiple contacts.",
            lead = "The recipient decides who can open the ciphertext; recipients come from public keys in Contacts.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "With only one contact, it is selected automatically; no need to choose every time.",
                        "With multiple contacts and no selection, encryption is blocked to avoid sending to the wrong person.",
                        "Long-press the floating ball to open the menu and choose a recipient; the menu only selects a person and no longer carries action rows.",
                        "The most recent choice is kept for the next tap.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "File encryption is entered from the home screen or system sharing, not through the long-press menu. " +
                        "The recipient list stores only names, public keys, and fingerprints; it does not store chat relationships.",
                ),
                GuideBlock.Note(
                    "You cannot choose yourself",
                    "Your own public key is rejected; adding the same public key twice is also rejected, so the contact list never contains two entries for one identity.",
                ),
            ),
        ),
        ("text" to "text-decrypt") to SectionText(
            title = "Results and the capsule",
            summary = "Signature checks, plaintext display, capsule expansion, and cleanup.",
            lead = "Only text recognized as ciphertext from this app is decrypted, with signature and sender checks.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "Quick mode tries to write the plaintext back to the same input field and stretches the ball into a capsule to show it.",
                        "Plaintext is not copied to the clipboard automatically; copy it yourself if needed.",
                        "The capsule stays about 20 seconds, or up to about 120 seconds when expanded; closing it or backgrounding clears it.",
                        "When the signature is valid it says “from X”; if the sender is not in Contacts it shows as unrecognized.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "A valid signature only shows that the ciphertext was signed by the matching private key and has not been modified; it does not prove who the person really is. " +
                        "You still need to verify fingerprints and where the public key came from.",
                ),
                GuideBlock.Note(
                    "Decryption failed",
                    "When the message says “not for this device key, or the content has been modified”, check the recipient first, then whether chat software altered the ciphertext.",
                ),
            ),
        ),
        ("text" to "text-clipboard") to SectionText(
            title = "Clipboard fallback, tiles, and hints",
            summary = "What happens when the input field cannot be read, and common messages.",
            lead = "When the input field cannot be read, the clipboard is the fallback path, and it is never read in advance.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "After copying plaintext, tap the ball: with a recipient, it encrypts and puts the ciphertext back into the clipboard, saying “Encrypted to X · long-press to paste”.",
                        "If there is plaintext but no recipient, it only hints to long-press the ball and choose a contact; it does not encrypt.",
                        "If the clipboard is ciphertext, it decrypts; if it is empty or unreadable, it only shows a hint and does not change the clipboard.",
                        "You can add a “Clipboard encryption” tile in Quick Settings; the clipboard is read only when you tap the tile or use the ball fallback.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "The app never pastes into the target app automatically and never sends automatically. You long-press to paste the ciphertext; " +
                        "the send action always happens in your chat app.",
                ),
                GuideBlock.Note(
                    "The clipboard is not a safe box",
                    "Other apps may also read the clipboard; after sending, copy some unrelated text to overwrite the ciphertext or plaintext.",
                ),
            ),
        ),

        ("files" to "file-home") to SectionText(
            title = "Home file entries",
            summary = "Multi-select, recursive folders, streaming, and result lists.",
            lead = "“Encrypt files / Decrypt files” on the home screen supports multiple files and whole folders.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "Processing is streaming; it never loads a whole file into memory. It has progress, cancel, and per-item results.",
                        "Folders are collected recursively; decryption only takes .aegix and skips other files, showing the count.",
                        "A single file larger than the selected volume size is split into volumes automatically; each volume stays under 2 GB. Folder queue limits 200 files / 4 GB / 5000 entries / 20 levels.",
                        "Each successful result can be shared, saved, or opened with another app; multiple results can use “Share all”.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "Results are written to the app-private directory first, then you decide whether to share, save, or open. Saving only copies to the location you choose; " +
                        "temporary results are still cleaned when you leave the page or by the lease rules.",
                ),
                GuideBlock.Note(
                    "Before encrypting",
                    "File encryption needs a local key and a recipient; decryption needs only the local key. If either is missing, the page blocks the start.",
                ),
            ),
        ),
        ("files" to "file-system") to SectionText(
            title = "System Open with and sharing",
            summary = "Single VIEW/SEND entry, classification, and mixed batches.",
            lead = "When another app hands a file to this app, there is a single “Crypto Assistant” entry.",
            blocks = listOf(
                GuideBlock.Paragraph(
                    "The app receives system open, share, and batch-share intents. It classifies by suffix and file header: " +
                        ".aegix or a recognized ciphertext header → decrypt; everything else → encrypt.",
                ),
                GuideBlock.Bullets(
                    listOf(
                        "A single file no longer shows a two-choice dialog; it goes straight to the matching page.",
                        "With a mixed batch it asks you to “decrypt only ciphertext / encrypt only regular files / cancel” and never guesses the direction for the whole batch.",
                        "If it cannot decide, or reading times out, it falls back to encryption; an uncertain regular file is never treated as ciphertext.",
                    ),
                ),
                GuideBlock.Note(
                    "The entry does not appear",
                    "That is filtering by the sender or the system, not a missing declaration in this app. If you cannot find the entry, use the home file entry instead.",
                ),
            ),
        ),
        ("files" to "file-quick") to SectionText(
            title = "Quick file handling",
            summary = "The HUD flow for external single files in Quick mode.",
            lead = "In Quick mode, an external single file can be encrypted or decrypted and confirmed before opening or sharing.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "Off by default; the switch is in Settings → “Floating ball & accessibility” → Work mode.",
                        "It only applies to a single file, Quick mode, an existing local identity and recipient, and an external target.",
                        "A visible HUD is shown while processing; going back or cancelling stops it and cleans this run’s temporary result.",
                        "When the conditions do not hold, it still opens the files page, exactly like the normal flow.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "It never chooses a chat target automatically and never sends automatically. The HUD only lets you confirm the direction and the result; " +
                        "the actual sending still happens in the target app.",
                ),
                GuideBlock.Note(
                    "Why it is off by default",
                    "An external entry that processes files directly skips the files page confirmation. It is off by default so the choice belongs to people who want the shortcut.",
                ),
            ),
        ),
        ("files" to "file-cleanup") to SectionText(
            title = "Saving, cleanup, and limits",
            summary = "Save/share/open, leases, VOLU, and capacity limits.",
            lead = "Results stay in the app-private directory first; then you save, share, or open them.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "“Save to…” copies to the location you choose; a successful save only marks it saved and does not delete the temporary result immediately.",
                        "After sharing or opening with another app succeeds, the result is kept for a 6-hour lease so the other side can reopen it from recent files.",
                        "Leaving the page, processing the next item, or a cold start cleans by the rules; “Clear cache” in Settings can clean manually.",
                        "While processing or saving, a manual cleanup is refused so files in use are not deleted.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "Android opens desktop VOLU volumes read-only and never creates internal multi-volume containers; folder encryption outputs a zip without automatic extraction, but the result page offers “Extract to folder” to write the structure safely.",
                ),
                GuideBlock.Note(
                    "Long-term storage",
                    "For long-term storage, use “Save to…” and choose a location yourself; shared plaintext stays a little longer in the app-private directory.",
                ),
            ),
        ),

        ("keys" to "key-local") to SectionText(
            title = "Local key",
            summary = "4096-bit RSA, Keystore wrapping, and the consequences of deletion.",
            lead = "The local key is your identity: the private key signs, and the public key lets others encrypt to you.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "Generates a 4096-bit RSA key; after creation it shows the fingerprint and creation time.",
                        "The private key is wrapped by Android Keystore; daily encryption and decryption need no password.",
                        "Copy the public key to others; they encrypt to you with it and you decrypt with the local private key.",
                        "Deleting the local key cannot be undone; contacts and old backups do not restore the identity automatically.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "On the keys page, tap the whole identity card to copy the public key. The fingerprint is a short digest of the public key, used for verification in person or over a trusted channel.",
                ),
                GuideBlock.Note(
                    "Do not rebuild often",
                    "When something goes wrong, check the backup, password, and recipient first; repeatedly deleting and creating keys makes old ciphertext impossible to open.",
                ),
            ),
        ),
        ("keys" to "key-backup") to SectionText(
            title = "Backup and restore",
            summary = "Full backups and import verification.",
            lead = "A backup is a password-protected .txt file; import accepts only full backups exported by this app.",
            blocks = listOf(
                GuideBlock.Paragraph(
                    "You can enable security-lock verification before export. The backup password applies only to this file; import checks that the public and private keys match. " +
                        "Legacy backup files are no longer accepted.",
                ),
                GuideBlock.Bullets(
                    listOf(
                        "A full backup keeps the creation time; a missing time is shown only as “Unknown”.",
                        "The last import time is recorded locally so you can tell which restore produced the current identity.",
                        "“Restore from file (replaces the current key)” overwrites the local identity; confirm the backup works first.",
                    ),
                ),
                GuideBlock.Note(
                    "Lost password",
                    "The backup password cannot be recovered; without it the backup cannot be decrypted. Keep the backup file and password separately.",
                ),
            ),
        ),
        ("keys" to "key-contacts") to SectionText(
            title = "Public keys, fingerprints, and contacts",
            summary = "Copying keys, verifying fingerprints, adding, and deduplication.",
            lead = "Contacts store only the other person’s public key and fingerprint, never through a server.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "On the keys page, tap the identity card to copy the public key and paste it to the other person.",
                        "On the contacts page, paste their public key, verify the fingerprint, and save; the name is only for local display.",
                        "Your own public key and duplicate keys are rejected; deleting a contact only affects the local list.",
                        "Matching fingerprints mean the keys are identical, but they do not prove the person on the other end is who you think.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "A public key can travel through chat, email, or any channel except scanning in person; for first trust, verify the fingerprint over a trusted channel. " +
                        "When the key changes later, verify it again.",
                ),
                GuideBlock.Note(
                    "Public keys from strangers",
                    "Do not trust a name alone. Names can be changed freely; the fingerprint is the real digest of the public key.",
                ),
            ),
        ),
        ("keys" to "key-errors") to SectionText(
            title = "Common errors",
            summary = "Passwords, mismatched keys, and duplicates.",
            lead = "Most errors come from the password, mismatched keys, or the backup format; reinstalling is not needed.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "Wrong password: make sure you entered the backup password, not the system screen-lock password.",
                        "Mismatched public and private keys: the backup is damaged or the wrong file was chosen; try another working backup.",
                        "Duplicate public key: the contact already exists; delete the old entry or use the existing one.",
                        "Unknown creation time: shown when backup metadata is missing; it does not mean the import failed.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "When decryption fails, first check whether the ciphertext is addressed to this device. If it says the recipient is wrong or the content changed, " +
                        "ask the sender to encrypt again; do not rebuild the local key repeatedly.",
                ),
                GuideBlock.Note(
                    "Reinstalling does not fix it",
                    "Uninstalling or clearing data loses the local key and contacts. Export a backup before considering a restore or replacement.",
                ),
            ),
        ),

        ("ball" to "ball-enable") to SectionText(
            title = "Turning on the floating ball",
            summary = "Overlay permission, drag snapping, and stopping the service.",
            lead = "The floating ball needs overlay permission; the switch is in Settings → “Floating ball & accessibility”.",
            blocks = listOf(
                GuideBlock.Steps(
                    listOf(
                        "Turn on the “Floating ball” switch.",
                        "Grant the overlay permission as prompted.",
                        "The ball appears on screen; drag it to an edge and it snaps after you release.",
                        "After rotation or split-screen, the ball is constrained inside the safe area again.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "Turning the switch off stops the foreground service and removes the ball. If the overlay permission is revoked in the system, the service also stops; " +
                        "when you return to the app the status shows paused.",
                ),
                GuideBlock.Note(
                    "The ball does not respond",
                    "Some systems block background launches, or the current page hides third-party overlays. Check the status in Settings first, then turn the switch off and on.",
                ),
            ),
        ),
        ("ball" to "ball-mode") to SectionText(
            title = "Work modes",
            summary = "Quick vs Standard, switching, and effects.",
            lead = "The difference between Quick and Standard mode is whether the input field is read and written.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "Quick mode: needs accessibility; tapping the ball reads and writes the current input field in place.",
                        "Standard mode: no accessibility, no clipboard monitoring; tapping the ball opens the text panel.",
                        "Switching takes effect immediately and closes an open menu or capsule.",
                        "The user-chosen mode takes priority over system permission status; when accessibility is not connected, Quick mode opens the guide panel first.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "Both modes share the same keys, contacts, and recipient. Standard mode is not a “degraded version”; it simply does not touch accessibility " +
                        "and suits people who do not want to grant it.",
                ),
                GuideBlock.Note(
                    "Standard mode does not read the input field",
                    "So it is normal that tapping the ball does not change text in place; it opens the text panel.",
                ),
            ),
        ),
        ("ball" to "ball-accessibility") to SectionText(
            title = "Accessibility disclosure and permission",
            summary = "Disclosure v3, separate consent, and fallback after turning it off.",
            lead = "Accessibility is a sensitive permission; the app reads the current input field only when the ball is tapped.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "The first time you enter a related flow, a disclosure is shown; you must consent separately before continuing.",
                        "You enable the service in system settings; the app never enables it itself and never pretends to be a system service.",
                        "It does not monitor the screen, collect events, or cache text; it reads the focused input field only when the ball is tapped.",
                        "After you deny or turn off accessibility, Quick mode falls back to the guide panel plus the clipboard.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "A new disclosure version asks for confirmation again. Declining does not affect file encryption, Standard mode, or key management.",
                ),
                GuideBlock.Note(
                    "System state vs running state",
                    "“Enabled in the system” and “service is running” are two different things. The settings page shows the real connection state.",
                ),
            ),
        ),
        ("ball" to "ball-tap") to SectionText(
            title = "Tap decisions and the guide panel",
            summary = "Not enabled, no focus, password fields, and clipboard fallback.",
            lead = "After a tap, it checks the current state, then decides whether to read the input field, read the clipboard, or open a panel.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "Accessibility off or the service not running: open the guide panel with enable, fix, and process-clipboard-once options.",
                        "Connected but no focus, empty field, or password field: show the reason and use the clipboard fallback.",
                        "Clipboard ciphertext: decrypt; plaintext with a recipient: encrypt and put it back into the clipboard.",
                        "Clipboard empty or unreadable: only show a hint; change nothing.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "The guide panel does not pre-read the clipboard; only when you tap “Process the clipboard once” does it read it. " +
                        "You can also switch Quick mode on or off right in the panel.",
                ),
                GuideBlock.Note(
                    "Never guess content",
                    "If it cannot read, it cannot read. The app does not walk other windows, read history, or try to bypass the other app’s restrictions.",
                ),
            ),
        ),
        ("ball" to "ball-tiles") to SectionText(
            title = "Tiles and vendor ROMs",
            summary = "Three tiles, how to add them, and background limits.",
            lead = "Quick Settings tiles provide shortcuts without opening the app.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "Three tiles: “Floating ball”, “Clipboard encryption”, and “Quick mode”.",
                        "Android 13 and later can add them in one tap; earlier versions require dragging them into Quick Settings following the dialog steps.",
                        "Tapping a tile when the service is not running tries to start it; tile state is in-process and may show off after a restart.",
                        "Some vendor ROMs do not implement one-tap add or restrict background starts; actual behavior wins.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "Tiles are only entries; they do not change the work mode or recipient. To choose a recipient, still long-press the floating ball.",
                ),
                GuideBlock.Note(
                    "Add failed",
                    "First make sure the app is not frozen by the system; then add it manually following the dialog steps, or turn on the floating ball in Settings.",
                ),
            ),
        ),

        ("security" to "security-lock") to SectionText(
            title = "Security lock",
            summary = "Protected actions, app lock, and verification methods.",
            lead = "The security lock protects infrequent sensitive actions and does not change daily encryption.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "Protected: export/import/delete the local key, export/clear logs, and change the log level.",
                        "Enabling and disabling the security lock also requires verification, so someone else cannot simply turn it off.",
                        "App lock is optional: a cold start always requires verification; returning from background can use “Immediately” or “After 10 minutes”, with 10 minutes as the default.",
                        "App lock covers only the main screen; the floating ball, ball panel, file entries, and system pickers are unaffected.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "Verification uses system fingerprint/face, with screen-lock credentials as a fallback when supported. The app does not store biometric data " +
                        "and does not draw its own verification UI. If no verification method is available, the security lock cannot be enabled.",
                ),
                GuideBlock.Note(
                    "Cancel means no action",
                    "If verification is cancelled or fails, the original action does not continue; there is no hidden “skip verification” path.",
                ),
            ),
        ),
        ("security" to "logs") to SectionText(
            title = "Logs",
            summary = "Levels, tap/long-press gestures, clearing, and redaction.",
            lead = "Logs record diagnostic facts only, never content.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "Levels: OFF / ERROR / WARN / INFO / DEBUG / VERBOSE.",
                        "Tap the level row to change the level, long-press it to export logs, and use the separate button to clear.",
                        "“Recent records” can be refreshed manually; export to see the full content.",
                        "Records event names, results, counts, durations, error categories, and input-field structure metadata.",
                        "Never writes plaintext, ciphertext, keys, passwords, clipboard content, file names, or paths.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "Changing the level affects only new records; existing logs are kept. At OFF, even crash traces are not written.",
                ),
                GuideBlock.Note(
                    "Export location",
                    "Export uses the system file picker and needs no storage permission; the exported .txt contains diagnostic facts only.",
                ),
            ),
        ),
        ("security" to "privacy") to SectionText(
            title = "Local data and privacy",
            summary = "No network, private directory, temporary results, and permission scope.",
            lead = "All content stays on the device; permissions and cleanup rules together form the boundary.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "No INTERNET permission, and no ads, analytics, or crash upload.",
                        "Keys live in the app-private directory with the private key wrapped by Keystore; contacts store only public keys and fingerprints.",
                        "Temporary results live in the app-private directory and are cleaned on leaving the page, by the 6-hour lease, and on cold start.",
                        "Accessibility reads only the focused input field; it does not monitor the screen, collect events, or cache text.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "Plaintext is never written to disk or logs. The backup is the only long-term file you must keep yourself; " +
                        "shared temporary results stay a little longer in the private directory.",
                ),
                GuideBlock.Note(
                    "When the device is controlled",
                    "Root, malicious system software, or someone else unlocking the device can bypass in-app protections; the app does not promise to defend against these cases.",
                ),
            ),
        ),

        ("trouble" to "trouble-ball") to SectionText(
            title = "The ball does not respond",
            summary = "Permissions, service, mode, and page restrictions.",
            lead = "Confirm permissions, service, and work mode first, then suspect the ball itself.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "Is overlay permission still granted, and is the floating ball switch in Settings on?",
                        "Is the accessibility service “enabled and running”; being enabled in the system alone is not enough.",
                        "Standard mode never reads the input field by design; a tap opens the text panel.",
                        "Some pages hide third-party overlays: the ball is visible but cannot be tapped.",
                    ),
                ),
                GuideBlock.Steps(
                    listOf(
                        "Settings → “Floating ball & accessibility” to check the status rows.",
                        "Turn the floating ball switch off and on, or re-grant accessibility.",
                        "If it still does not respond, restart the app and try a normal input field.",
                    ),
                ),
                GuideBlock.Note(
                    "Do not tap repeatedly",
                    "Repeated taps do not improve the success rate; check the status first, then decide whether to fix permissions or use the clipboard.",
                ),
            ),
        ),
        ("trouble" to "trouble-wechat") to SectionText(
            title = "WeChat/QQ cannot read the input field",
            summary = "The clipboard path when the other app hides nodes.",
            lead = "Some apps hide third-party accessibility nodes; not reading is their behavior, not a local permission problem.",
            blocks = listOf(
                GuideBlock.Paragraph(
                    "The WeChat chat input currently behaves this way: accessibility is enabled in the system, but this app cannot read an editable node. " +
                        "The app does not try to bypass this or pretend to be a system accessibility service.",
                ),
                GuideBlock.Steps(
                    listOf(
                        "Copy the plaintext to encrypt in the chat input.",
                        "Choose the recipient first in Contacts or by long-pressing the ball.",
                        "Tap the ball: the ciphertext is put back into the clipboard with “Encrypted to X · long-press to paste”.",
                        "Return to the chat, long-press to paste and send; when you receive ciphertext, copy it and tap the ball to decrypt.",
                    ),
                ),
                GuideBlock.Note(
                    "Do not expect “replaced”",
                    "When the other app does not accept writes, no write-back happens. Trust the actual field content; use the clipboard when it fails.",
                ),
            ),
        ),
        ("trouble" to "trouble-writeback") to SectionText(
            title = "Write-back and decryption failures",
            summary = "Snapshots, password fields, recipients, and write restrictions.",
            lead = "Use the message to tell whether it is a read, snapshot, password-field, or recipient problem.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "Cannot read or empty field: use the clipboard path.",
                        "Content changed before write-back: the app abandons the write and says so; it never overwrites what you just typed.",
                        "Password field/PIN: skipped on purpose, never read or written.",
                        "Wrong recipient or content modified: the ciphertext is not for this device or was changed in transit.",
                        "No change after write-back: some apps do not accept writes; use the clipboard.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "A failure never damages the original input field or clipboard text. Logs contain only results and reasons, never text content.",
                ),
                GuideBlock.Note(
                    "Back up before troubleshooting",
                    "Export a working backup before replacing keys or reinstalling; do not delete the key to “fix” decryption failures.",
                ),
            ),
        ),
        ("trouble" to "trouble-entry") to SectionText(
            title = "The system entry does not appear",
            summary = "Sender/ROM filtering and the file-entry fallback.",
            lead = "The candidate list is decided by the sender and the system; this app can only declare what it accepts.",
            blocks = listOf(
                GuideBlock.Paragraph(
                    "Whether WeChat, QQ, or a file manager shows “Crypto Assistant” in Open with / Share depends on whether the other side exposes the file, " +
                        "filters third-party apps, and whether the ROM restricts background starts or the candidate list.",
                ),
                GuideBlock.Bullets(
                    listOf(
                        "Use the home “Encrypt/Decrypt files” entry as a fallback; functionality is unchanged.",
                        "Shares with no type at all and file:// remain known limits.",
                        "Some apps only allow sharing images or files, not arbitrary text.",
                    ),
                ),
                GuideBlock.Note(
                    "Not a permission problem",
                    "The app already declares the file types it accepts; a missing entry is usually sender filtering, so reinstalling repeatedly will not help.",
                ),
            ),
        ),
        ("trouble" to "trouble-feedback") to SectionText(
            title = "Exporting logs and reporting",
            summary = "Log export steps and privacy boundaries when reporting.",
            lead = "When reporting a problem, provide logs and version info, never plaintext or keys.",
            blocks = listOf(
                GuideBlock.Steps(
                    listOf(
                        "Settings → “Logs”: make sure the current level is not OFF.",
                        "Long-press the level row to export logs and choose a save location.",
                        "Find the version and build UUID in “Settings → About”.",
                        "Provide the exported .txt together with these two pieces of information.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "Logs contain no content. Describe the problem as “app X, page Y” instead of real names, contacts, or file names; " +
                        "do not send backups, private keys, passwords, or ciphertext from chat screenshots.",
                ),
                GuideBlock.Note(
                    "Clear first when needed",
                    "If the logs contain unrelated history, clear them first, reproduce the problem once, then export.",
                ),
            ),
        ),

        ("appendix" to "terms") to SectionText(
            title = "Glossary",
            summary = "Public key, private key, fingerprint, ciphertext, signature, and more.",
            lead = "When you meet an unfamiliar word in the text, come back here.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "Public key: an encryption key you can share; others use it to encrypt to you.",
                        "Private key: the core of the local identity, used to decrypt and sign; it never leaves the device.",
                        "Fingerprint: a short digest of a public key, used to check whether two keys are identical.",
                        "Plaintext: unencrypted original content; ciphertext: content after encryption.",
                        "Signature: proves content came from a certain private key and was not modified.",
                        "Wire format: the encoding and structure agreement for ciphertext between devices.",
                        "VOLU: a desktop volume container; Android opens it read-only and never creates it.",
                        "Lease: how long a temporary result is kept after sharing/opening; currently about 6 hours.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "This explains only the parts used by this app. For complete cryptographic definitions, see public textbooks; this guide is not a substitute for a professional audit.",
                ),
            ),
        ),
        ("appendix" to "formats") to SectionText(
            title = "File formats and compatibility",
            summary = ".aegix, VOLU, zip, and size limits.",
            lead = "Only .aegix is recognized; legacy .enc and the old internal VOLU are not.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        ".aegix: the only suffix for single-file ciphertext; .enc is no longer treated as ciphertext.",
                        "VOLU: an external volume container; both Android and the desktop app can write and read it.",
                        "Folder and merged multi-volume output is a zip; it does not auto-extract, but the result page can extract it to a folder, appending _2 on duplicate names and never overwriting existing files.",
                        "A single file larger than the selected volume size is split into volumes under 2 GB each; folder queue 200 files / 4 GB / 5000 entries / 20 levels.",
                        "Shares the RSA-OAEP + AES-256-GCM wire format with the desktop app.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "A suffix is not a security boundary: classification also checks the file header. Renaming a regular file to .aegix does not make it ciphertext.",
                ),
                GuideBlock.Note(
                    "Space for large files",
                    "Processing needs free space close to the result size; output is written to the app-private directory first, then saved or shared.",
                ),
            ),
        ),
        ("appendix" to "version") to SectionText(
            title = "Version, license, and build info",
            summary = "Version, GPLv3, build UUID, and dependencies.",
            lead = "Version, build UUID, and license are in Settings → “About Crypto Assistant”.",
            blocks = listOf(
                GuideBlock.Paragraph(
                    "The version and versionCode are shown in “Settings → About Crypto Assistant”; that page is authoritative. The app is released under GNU GPL v3.0 (GPL-3.0-only) " +
                        "with source provided with the project; it is distributed open-source and via sideloading, not updated through an app store.",
                ),
                GuideBlock.Bullets(
                    listOf(
                        "The About page shows the version, build type, build UUID, and build time.",
                        "The build UUID and time only distinguish installation packages; they contain no device or user information.",
                        "You can view the full GPLv3 text and the open-source dependency list.",
                    ),
                ),
                GuideBlock.Note(
                    "When reporting",
                    "Providing the version and build UUID helps identify the package; do not attach keys, backups, or plaintext.",
                ),
            ),
        ),
        ("appendix" to "settings") to SectionText(
            title = "Settings reference",
            summary = "Defaults and effects at a glance.",
            lead = "Defaults and effects of every switch.",
            blocks = listOf(
                GuideBlock.Bullets(
                    listOf(
                        "Appearance: light/dark/follow system; optional brand color and pure black dark theme; dynamic color is off by default.",
                        "Floating ball: off by default; enabling needs overlay permission.",
                        "Floating ball appearance: small/standard/large size and three opacity levels; affects the ball only.",
                        "Work mode: Quick/Standard; Quick mode needs accessibility.",
                        "Quick file handling: off by default; only applies to external single files in Quick mode.",
                        "Security lock: off by default; protects sensitive key and log actions.",
                        "App lock: off by default; re-verification can be “Immediately” or “After 10 minutes”.",
                        "Logs: the level is changeable; tap to change, long-press to export, and use the separate button to clear.",
                        "User guide: home entry; content updates with the version.",
                    ),
                ),
                GuideBlock.Paragraph(
                    "Settings follow the mode they belong to; infrequent switches are not on the home screen. Changing settings does not affect existing ciphertext, backups, or contacts.",
                ),
                GuideBlock.Note(
                    "Restoring defaults",
                    "There is no “reset all settings”; change items back one by one. Keys and files are unaffected.",
                ),
            ),
        ),
    )
}
