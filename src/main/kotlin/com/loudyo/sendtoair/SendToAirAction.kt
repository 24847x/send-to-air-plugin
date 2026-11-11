package com.loudyo.sendtoair

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.Timer

class SendToAirAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val editorFile = editorFile(e)
        val refs = if (editor != null && editorFile != null) {
            listOf(fileReference(project, editorFile, selectedLines(editor), fromEditor = true))
        } else {
            // Project view (or another file tree): one reference per selected file/folder.
            selectedFiles(e).map { fileReference(project, it, lines = null, fromEditor = false) }.takeIf { it.isNotEmpty() } ?: return
        }

        if (insertIntoOpenSession(project, refs)) return

        // No Air session is open: start one with Air's own "New Session" action, then wait for its prompt to appear.
        val newSession = ActionManager.getInstance().getAction(NEW_SESSION_ACTION_ID)
        if (newSession == null) {
            notifyFailure(project, "Air is not available. Make sure the JetBrains Air plugin is installed and enabled.")
            return
        }
        val source = editor?.contentComponent ?: e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
        ActionManager.getInstance().tryToExecute(newSession, null, source, ActionPlaces.UNKNOWN, true)
        retryInsert(project, refs)
    }

    /**
     * A file or folder to mention; [lines] is the 1-based selected line range, if any.
     * [fromEditor] keeps the Air prompt's original editor format (file name only).
     */
    private data class Reference(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val lines: IntRange?,
        val fromEditor: Boolean,
    )

    // Only real files: Air session tabs are virtual files too and must not be referenced.
    private fun editorFile(e: AnActionEvent): VirtualFile? =
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isInLocalFileSystem }

    private fun selectedFiles(e: AnActionEvent): List<VirtualFile> =
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty().filter { it.isInLocalFileSystem }

    /** Path relative to the project, falling back to the name for files outside it. */
    private fun fileReference(project: Project, file: VirtualFile, lines: IntRange?, fromEditor: Boolean): Reference {
        val path = project.guessProjectDir()?.let { VfsUtilCore.getRelativePath(file, it) }?.takeIf { it.isNotEmpty() }
            ?: file.name
        return Reference(file.name, path, file.isDirectory, lines, fromEditor)
    }

    private fun selectedLines(editor: Editor): IntRange? {
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return null

        val doc = editor.document
        val startLine = doc.getLineNumber(selection.selectionStart) + 1
        // A selection ending at column 0 doesn't include that line.
        val endOffset = selection.selectionEnd
        var endLine = doc.getLineNumber(endOffset) + 1
        if (endLine > startLine && endOffset == doc.getLineStartOffset(endLine - 1)) endLine--
        return startLine..endLine
    }

    /**
     * Air prompt format: `@file:UserService.kt [lines from 42 to 58]` for editor selections,
     * `@file:src/Main.kt` / `@folder:src/checkout/` (project-relative) for files and folders.
     */
    private fun formatForAirPrompt(refs: List<Reference>): String =
        refs.joinToString(separator = " ", postfix = " ") { ref ->
            when {
                ref.isDirectory -> "@folder:${ref.path}/"
                ref.fromEditor -> {
                    val lines = ref.lines
                    when {
                        lines == null -> "@file:${ref.name}"
                        lines.first == lines.last -> "@file:${ref.name} [line ${lines.first}]"
                        else -> "@file:${ref.name} [lines from ${lines.first} to ${lines.last}]"
                    }
                }
                else -> "@file:${ref.path}"
            }
        }

    /** Claude Code format: `@src/File.kt#L42-58`, `@src/File.kt#L42`, `@src/File.kt`, `@src/folder/`. */
    private fun formatForClaudeCode(refs: List<Reference>): String =
        refs.joinToString(separator = " ", postfix = " ") { ref ->
            val lines = ref.lines
            when {
                ref.isDirectory -> "@${ref.path}/"
                lines == null -> "@${ref.path}"
                lines.first == lines.last -> "@${ref.path}#L${lines.first}"
                else -> "@${ref.path}#L${lines.first}-${lines.last}"
            }
        }

    private fun retryInsert(project: Project, refs: List<Reference>) {
        var attempts = 0
        val timer = Timer(RETRY_DELAY_MS, null)
        timer.addActionListener {
            attempts++
            when {
                project.isDisposed || insertIntoOpenSession(project, refs) -> timer.stop()
                attempts >= MAX_RETRIES -> {
                    timer.stop()
                    notifyFailure(project, "Could not find an Air session to insert the reference into. Open an Air session and try again.")
                }
            }
        }
        timer.start()
    }

    private fun notifyFailure(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    /**
     * Air shows its chat prompt inside session editor tabs ("New Session", ...) or in the "air.chat"
     * tool window when a session is docked there. The "Agent Sessions" tool window is only a session list.
     * Agent sessions built on Air's GUI (Junie) use Air's prompt field; terminal-based agents (Claude Code)
     * run their own CLI in an IDE terminal, so the text is pasted into that terminal instead.
     */
    private fun insertIntoOpenSession(project: Project, refs: List<Reference>): Boolean {
        val fileEditorManager = FileEditorManager.getInstance(project)

        // Prefer sessions visible in an editor split, then any other open session tab.
        val editors = (fileEditorManager.selectedEditors.asSequence() + fileEditorManager.allEditors.asSequence()).distinct()
        for (fileEditor in editors) {
            // Regular code editors (real files) are not sessions; Air session tabs are virtual files.
            if (fileEditor is TextEditor && fileEditor.file?.isInLocalFileSystem != false) continue
            val send = findSender(fileEditor.component, fileEditor.preferredFocusedComponent) ?: continue
            focusEditor(fileEditorManager, fileEditor)
            send(refs)
            return true
        }

        // Session may be docked in a tool window: "air.chat" first, then any other visible Air tool window
        // (e.g. "air.threads" hosts terminal-based sessions). The regular Terminal tool window is never used.
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val ids = listOf(CHAT_TOOL_WINDOW_ID) + toolWindowManager.toolWindowIds.filter { it.startsWith("air.") && it != CHAT_TOOL_WINDOW_ID }
        for (id in ids) {
            val toolWindow = toolWindowManager.getToolWindow(id) ?: continue
            if (id != CHAT_TOOL_WINDOW_ID && !toolWindow.isVisible) continue
            val content = toolWindow.contentManager.selectedContent ?: continue
            val send = findSender(content.component, content.preferredFocusableComponent) ?: continue
            toolWindow.activate({ send(refs) }, true)
            return true
        }
        return false
    }

    private fun findSender(root: JComponent, preferredFocused: JComponent?): ((List<Reference>) -> Unit)? {
        findPromptField(root)?.let { field ->
            return { refs -> insert(field, formatForAirPrompt(refs)) }
        }
        val terminal = AirTerminalSender.findSender(root, preferredFocused) ?: return null
        return { refs -> terminal(formatForClaudeCode(refs)) }
    }

    private fun focusEditor(fileEditorManager: FileEditorManager, fileEditor: FileEditor) {
        val file = fileEditor.file ?: return
        fileEditorManager.openFile(file, true)
    }

    private fun findPromptField(root: JComponent): EditorTextField? =
        UIUtil.findComponentsOfType(root, EditorTextField::class.java)
            .firstOrNull { it.javaClass.name == AIR_PROMPT_FIELD_CLASS }

    private fun insert(field: EditorTextField, message: String) {
        val existing = field.text.trimEnd()
        val newText = if (existing.isEmpty()) message else "$existing\n$message"
        field.text = newText
        field.requestFocusInWindow()
        field.editor?.caretModel?.moveToOffset(newText.length)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && (
            (e.getData(CommonDataKeys.EDITOR) != null && editorFile(e) != null) ||
                selectedFiles(e).isNotEmpty()
            )
    }

    private companion object {
        const val AIR_PROMPT_FIELD_CLASS = "com.intellij.air.frontend.prompt.AirPromptEditorTextField"
        const val NEW_SESSION_ACTION_ID = "AgentThreads.MainToolbar.NewThread"
        const val NOTIFICATION_GROUP_ID = "Send to Air"
        const val CHAT_TOOL_WINDOW_ID = "air.chat"
        const val RETRY_DELAY_MS = 100
        const val MAX_RETRIES = 30
    }
}
