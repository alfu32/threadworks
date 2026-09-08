package com.threadwork.app.identity

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserIdentityTest {
    @Test
    fun `designator follows username full name email and system user priority`() {
        val expiration = Instant.parse("2026-09-15T00:00:00Z")

        assertEquals(
            "octocat@github",
            UserIdentity(OAuthProvider.GITHUB, "cat@example.test", "octocat", "The Octocat", expiresAt = expiration)
                .designator("local"),
        )
        assertEquals(
            "Ada Lovelace@google",
            UserIdentity(OAuthProvider.GOOGLE, "ada@example.test", fullName = "Ada Lovelace", expiresAt = expiration)
                .designator("local"),
        )
        assertEquals(
            "ada@example.test",
            UserIdentity(OAuthProvider.MICROSOFT, "ada@example.test", expiresAt = expiration).designator("local"),
        )
        assertEquals("local", null.designator("local"))
    }

    @Test
    fun `identity is shared through preferences and removed after one week`() {
        val preferences = Preferences.userRoot().node("com/threadwork/tests/identity/${UUID.randomUUID()}")
        val avatarFile = Files.createTempDirectory("threadwork-identity-test").resolve("avatar.png")
        var now = Instant.parse("2026-09-08T12:00:00Z")
        val store = UserIdentityStore(preferences, avatarFile) { now }
        try {
            val photoBytes = ByteArrayOutputStream().use { output ->
                ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", output)
                output.toByteArray()
            }
            val saved = store.save(
                OAuthUserProfile(
                    provider = OAuthProvider.GOOGLE,
                    emailAddress = "ada@example.test",
                    username = "ada",
                    fullName = "Ada Lovelace",
                    profilePhotoUrl = "https://example.test/ada.png",
                    profilePhotoBytes = photoBytes,
                ),
            )

            assertEquals(now.plus(UserIdentityStore.SESSION_DURATION), saved.expiresAt)
            assertEquals(saved, UserIdentityStore(preferences, avatarFile) { now }.load())
            assertEquals(64, store.avatarImage()?.width)

            now = now.plus(8, ChronoUnit.DAYS)
            assertNull(store.load())
            assertEquals("", preferences.get("provider", ""))
            assertFalse(Files.exists(avatarFile))
        } finally {
            runCatching { preferences.removeNode() }
            runCatching { Files.deleteIfExists(avatarFile) }
            runCatching { Files.deleteIfExists(avatarFile.parent) }
        }
    }

    @Test
    fun `generated avatar derives initials and md5 color from designator`() {
        assertEquals("AG", avatarInitials("alice@github"))
        assertEquals(Color(0x61, 0x38, 0x07), avatarBaseColor("alice@github"))
        assertEquals(28, userAvatarIcon("alice@github", null, 28).iconWidth)
    }

    @Test
    fun `oauth authentication fails before browser launch when client id is absent`() {
        var browserOpened = false
        val client = DesktopOAuthClient(
            registrationProvider = { OAuthClientRegistration("") },
            openBrowser = { browserOpened = true },
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            client.authenticate(OAuthProvider.GOOGLE)
        }

        assertTrue(failure.message.orEmpty().contains("THREADWORK_GOOGLE_CLIENT_ID"))
        assertFalse(browserOpened)
    }
}
