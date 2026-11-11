package com.loudyo.sendtoair

import com.intellij.ide.DataManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.extensions.PluginId
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

/**
 * Sends text to a terminal-based Air session (e.g. Claude Code), which renders the agent's own TUI
 * in an IDE terminal instead of Air's prompt field.
 *
 * Kept in its own class: it touches Terminal plugin classes, which are an optional dependency.
 */
internal object AirTerminalSender {

    private val terminalPluginId = PluginId.getId("org.jetbrains.plugins.terminal")

    /** Returns a function that pastes text into the terminal found under [root] and focuses it, or null. */
    fun findSender(root: JComponent, preferredFocused: JComponent?): ((String) -> Unit)? {
        if (PluginManagerCore.isDisabled(terminalPluginId) || PluginManagerCore.getPlugin(terminalPluginId) == null) return null
        val view = try {
            findTerminalView(root, preferredFocused)
        } catch (_: LinkageError) {
            null // Terminal API not available in this IDE version
        } ?: return null

        return { text ->
            // Bracketed paste so the agent CLI treats it as pasted input, without pressing Enter.
            view.createSendTextBuilder().useBracketedPasteMode().send(text)
            view.preferredFocusableComponent.requestFocusInWindow()
        }
    }

    private fun findTerminalView(root: JComponent, preferredFocused: JComponent?): TerminalView? {
        // The terminal host differs between IDE/Air versions, so try the preferred component, the
        // editor components, and finally every component under the session root.
        val candidates = sequence {
            listOfNotNull(preferredFocused, root).forEach { yield(it) }
            UIUtil.findComponentsOfType(root, EditorComponentImpl::class.java).forEach { yield(it) }
            UIUtil.findComponentsOfType(root, JComponent::class.java).forEach { yield(it) }
        }
        return candidates.firstNotNullOfOrNull { component ->
            DataManager.getInstance().getDataContext(component).getData(TerminalView.DATA_KEY)
        }
    }
}
