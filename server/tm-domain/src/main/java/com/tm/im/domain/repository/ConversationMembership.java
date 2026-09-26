package com.tm.im.domain.repository;

import com.tm.im.domain.entity.Conversation;
import com.tm.im.domain.entity.ConversationMember;

/**
 * 「我的会话列表」的一行：会话本身，加上<b>我在这个会话里</b>的那条成员关系。
 *
 * <p><b>为什么要有这个组合类型</b>：拉会话列表需要两样东西——会话标题/类型/创建时间，
 * 以及<b>我自己的</b> {@code last_read_seq}（未读数 = 会话最大 seq 减它）。
 * 它们分别属于 {@code conversation} 与 {@code conversation_member} 两张表，
 * 若只返回 {@code List<Conversation>}，调用方就得拿着 convId 再查一次成员行
 * （N 次点查），而「我」的消息在仓储里已经查出来了。
 *
 * <p>它与 {@code listMemberIds} 的区别是<b>视角</b>：那个问「这个会话里都有谁」（群成员、
 * 扇出目标），这个问「我参与了哪些会话」（会话列表）。混用会让「拉我的会话列表」
 * 退化成「遍历全部会话再逐个查成员」。
 *
 * <p>字段顺序刻意是「会话在前、成员在后」：读代码时先知道是哪个会话，
 * 再看我在里面的状态，与 UI 上「先看到会话、再看到未读红点」的顺序一致。
 */
public record ConversationMembership(Conversation conversation, ConversationMember member) {
}
