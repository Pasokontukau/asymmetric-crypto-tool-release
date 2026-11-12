package com.cryptoassistant.app.feature.ball

import com.cryptoassistant.app.data.keys.ContactKey

/**
 * 文字加密的接收方只由长按菜单设置。
 *
 * 规则（2026-10-24 所有者确认）：
 * - 上次选择仍在联系人里 → 用它；
 * - 否则只有唯一联系人 → 自动用它（显示在面板里，不会静默发给多人中的某一个）；
 * - 多个联系人且没有选择 → 返回 null，由调用方阻止加密并提示去长按菜单选择。
 */
fun resolveRecipient(contacts: List<ContactKey>, lastContactId: String?): ContactKey? =
    contacts.firstOrNull { it.id == lastContactId } ?: contacts.singleOrNull()
