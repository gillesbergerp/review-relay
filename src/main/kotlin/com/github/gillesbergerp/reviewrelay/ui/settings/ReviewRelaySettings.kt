package com.github.gillesbergerp.reviewrelay.ui.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "ReviewRelaySettings", storages = [Storage("review-relay.xml")])
@Service(Service.Level.APP)
class ReviewRelaySettings : PersistentStateComponent<ReviewRelaySettings.State> {

    class State {
        var serverUrl: String = ""
        var username: String = ""
        var agent: String = ""
        var notifyWhenIdle: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    var password: String?
        get() = PasswordSafe.instance.getPassword(CREDENTIALS)?.takeIf { it.isNotEmpty() }
        set(value) = PasswordSafe.instance.setPassword(CREDENTIALS, value)

    companion object {
        private val CREDENTIALS = CredentialAttributes(generateServiceName("Review Relay", "opencode-server"))

        val instance: ReviewRelaySettings
            get() = ApplicationManager.getApplication().getService(ReviewRelaySettings::class.java)
    }
}
