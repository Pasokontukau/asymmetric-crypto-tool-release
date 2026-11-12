package com.cryptoassistant.app.feature.ball

import com.cryptoassistant.app.data.keys.ContactKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 接收方只在长按菜单设置；唯一联系人可自动选中，多联系人未选择时必须阻止。 */
class RecipientSelectionTest {

    private val alice = contact("alice")
    private val bob = contact("bob")

    @Test
    fun `last selected contact wins`() {
        assertEquals(bob, resolveRecipient(listOf(alice, bob), "bob"))
    }

    @Test
    fun `single contact is auto selected`() {
        assertEquals(alice, resolveRecipient(listOf(alice), null))
    }

    @Test
    fun `multiple contacts without a choice returns null`() {
        assertNull(resolveRecipient(listOf(alice, bob), null))
    }

    @Test
    fun `deleted selection falls back only when exactly one contact remains`() {
        assertNull(resolveRecipient(listOf(alice, bob), "deleted"))
        assertEquals(alice, resolveRecipient(listOf(alice), "deleted"))
    }

    @Test
    fun `empty contact list returns null`() {
        assertNull(resolveRecipient(emptyList(), "alice"))
    }

    private fun contact(id: String) = ContactKey(
        id = id,
        name = id,
        publicKeyBase64 = "test",
        fingerprint = "fingerprint-$id",
        addedAt = 0L,
    )
}
