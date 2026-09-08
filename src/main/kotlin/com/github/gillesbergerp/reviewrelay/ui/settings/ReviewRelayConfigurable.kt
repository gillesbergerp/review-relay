package com.github.gillesbergerp.reviewrelay.ui.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ValidationInfoBuilder
import java.net.URI

class ReviewRelayConfigurable : BoundConfigurable("Review Relay") {

    private val settings = ReviewRelaySettings.instance
    private lateinit var passwordField: JBPasswordField

    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox("Notify when the agent finishes a review, or stops to ask something")
                .bindSelected(settings.state::notifyWhenIdle)
        }
        // Everything below reaches only the OpenCode backend. Grouped and said outright, because a
        // Claude Code user opening this page saw four fields that do nothing for them.
        group("OpenCode") {
            row {
                comment(
                    "Claude Code and other MCP clients need none of this — they are set up through " +
                        "<b>Set Up Agent Access</b> in the tool window instead."
                )
            }
            row("Server URL:") {
                textField()
                    .bindText(settings.state::serverUrl)
                    .columns(COLUMNS_LARGE)
                    // Caught here rather than as "No OpenCode server found" much later, with nothing
                    // pointing back at the field that caused it - and on apply too, or the error
                    // shows and the address is saved anyway.
                    .validationOnInput { field -> addressProblem(field.text) }
                    .validationOnApply { field -> addressProblem(field.text) }
                    .comment(
                        "Leave empty to look for the OpenChamber desktop app, which re-exposes the " +
                            "OpenCode API, then a managed OpenCode server, then " +
                            "<code>http://127.0.0.1:4096</code>."
                    )
            }
            row("OpenCode agent:") {
                textField()
                    .bindText(settings.state::agent)
                    .columns(20)
                    // Not the backend the tool window's own picker calls an agent: this is the
                    // sub-agent name OpenCode itself runs a session under.
                    .comment("The OpenCode sub-agent to run as. Leave empty to keep the session's own.")
            }
            row("Username:") {
                textField()
                    .bindText(settings.state::username)
                    .columns(20)
                    .comment("Default: <code>opencode</code>")
            }
            row("Password:") {
                passwordField = passwordField().columns(20).component
            }.comment(
                "Only needed for a server started with <code>OPENCODE_SERVER_PASSWORD</code>. " +
                    "Leave empty to read that variable from the environment."
            )
        }
        row {
            comment("These settings are shared by every project open in this IDE.")
        }

        // Read once when the panel opens: settings polls isModified, and a keychain-backed store
        // can take long enough for that to be felt on the EDT.
        var stored = settings.password.orEmpty()
        onReset {
            stored = settings.password.orEmpty()
            passwordField.text = stored
        }
        onIsModified { String(passwordField.password) != stored }
        onApply {
            stored = String(passwordField.password)
            settings.password = stored.takeIf { it.isNotEmpty() }
        }
    }
}

/** Null when [typed] is an address or is empty, which means look for a server instead. */
private fun ValidationInfoBuilder.addressProblem(typed: String): ValidationInfo? {
    val address = typed.trim()
    if (address.isEmpty()) return null
    val parsed = runCatching { URI(address).takeIf { it.host != null } }.getOrNull()
    return if (parsed != null) null else error("Not an address, e.g. http://127.0.0.1:4096")
}
