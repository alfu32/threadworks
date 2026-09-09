package com.threadwork.app.ui

import com.threadwork.app.identity.DesktopOAuthClient
import com.threadwork.app.identity.OAuthProvider
import com.threadwork.app.identity.ThreadworkUserIdentity
import com.threadwork.app.identity.UserIdentity
import com.threadwork.app.identity.UserIdentityStore
import com.threadwork.app.identity.designator
import com.threadwork.app.identity.userAvatarIcon
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.util.concurrent.ExecutionException
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingConstants
import javax.swing.SwingWorker
import javax.swing.Timer

internal class UserIdentityTitleBar(
    private val dialogParent: Component,
    private val identityStore: UserIdentityStore = ThreadworkUserIdentity.store,
    private val oauthClient: DesktopOAuthClient = DesktopOAuthClient(),
    private val onStatus: (String) -> Unit = {},
) : JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)) {
    private val designatorLabel = JLabel()
    private val avatarButton = JButton().apply {
        preferredSize = Dimension(34, 34)
        minimumSize = preferredSize
        maximumSize = preferredSize
        horizontalAlignment = SwingConstants.CENTER
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        isContentAreaFilled = false
        isFocusPainted = false
        toolTipText = "User identity"
        addActionListener { showIdentityMenu() }
    }
    private var renderedIdentity: UserIdentity? = null
    private val sharedIdentityRefreshTimer = Timer(30_000) { refresh() }.apply {
        isRepeats = true
        start()
    }

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, 4, 0, 6)
        add(designatorLabel)
        add(avatarButton)
        refresh(force = true)
    }

    fun refresh(force: Boolean = false) {
        val identity = identityStore.load()
        if (!force && identity == renderedIdentity) return
        renderedIdentity = identity
        val designator = identity.designator()
        designatorLabel.text = designator
        avatarButton.icon = userAvatarIcon(designator, identityStore.avatarImage(), 28)
        avatarButton.toolTipText = "User identity"
        revalidate()
        repaint()
    }

    private fun showIdentityMenu() {
        val identity = identityStore.load()
        JPopupMenu().apply {
            add(identitySummary(identity))
            addSeparator()
            OAuthProvider.entries.forEach { provider ->
                add(JMenuItem("Sign in with ${provider.label}").apply {
                    addActionListener { signIn(provider) }
                })
            }
            if (identity != null) {
                addSeparator()
                add(JMenuItem("Sign out").apply {
                    addActionListener {
                        identityStore.clear()
                        refresh(force = true)
                        onStatus("Signed out; using the local system identity")
                    }
                })
            }
            show(avatarButton, avatarButton.width - preferredSize.width, avatarButton.height)
        }
    }

    private fun signIn(provider: OAuthProvider) {
        avatarButton.isEnabled = false
        designatorLabel.text = "Signing in with ${provider.label}..."
        object : SwingWorker<UserIdentity, Unit>() {
            override fun doInBackground(): UserIdentity = identityStore.save(oauthClient.authenticate(provider))

            override fun done() {
                avatarButton.isEnabled = true
                runCatching { get() }
                    .onSuccess { identity ->
                        refresh(force = true)
                        onStatus("Signed in as ${identity.designator()}")
                    }
                    .onFailure { failure ->
                        refresh(force = true)
                        val cause = (failure as? ExecutionException)?.cause ?: failure
                        JOptionPane.showMessageDialog(
                            dialogParent,
                            cause.message ?: "Login failed.",
                            "User Login",
                            JOptionPane.ERROR_MESSAGE,
                        )
                    }
            }
        }.execute()
    }

    private fun identitySummary(identity: UserIdentity?): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(8, 10, 8, 16)
        isOpaque = false

        val designator = identity.designator()
        add(JLabel(designator).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.BOLD)
        })
        if (identity == null) {
            add(summaryDetail("Local system identity"))
        } else {
            identity.fullName.takeIf { it.isNotBlank() && it != designator }?.let {
                add(summaryDetail(it))
            }
            identity.emailAddress.takeIf(String::isNotBlank)?.let {
                add(summaryDetail(it))
            }
            add(summaryDetail(identity.provider.label))
            identity.userId.takeIf(String::isNotBlank)?.let {
                add(summaryDetail("User ID: $it"))
            }
            identity.role.takeIf(String::isNotBlank)?.let {
                add(summaryDetail("Role: $it"))
            }
            add(summaryDetail("Login expires ${identity.expiresAt}"))
        }
    }

    private fun summaryDetail(value: String): JLabel = JLabel(value).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = font.deriveFont(font.size2D - 1f)
    }
}
