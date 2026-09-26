package com.tm.im.core.conversation;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.message.MessageCommandPort;
import com.tm.im.core.message.MessageService;
import com.tm.im.domain.entity.Message;
import com.tm.im.domain.enums.MessageType;

import java.util.ArrayList;
import java.util.List;

/**
 * 记录型消息命令替身。
 *
 * <p>{@code markRead} 会真的写到 {@link InMemoryConversations}：被验证的那条规则是
 * 「响应里的 last_read_seq 是<b>生效后</b>的值」，若替身只记一笔，那条规则就永远测不出差异。
 */
class RecordingMessageCommands implements MessageCommandPort {

    private final InMemoryConversations conversations;
    private final List<MessageService.SendCommand> sent = new ArrayList<>();

    /** 让下一次 send 抛这个（模拟「系统消息写不进去」）。 */
    RuntimeException nextSendFailure;

    int markReadCalls;

    RecordingMessageCommands(InMemoryConversations conversations) {
        this.conversations = conversations;
    }

    List<MessageService.SendCommand> sent() {
        return List.copyOf(sent);
    }

    @Override
    public MessageService.SendOutcome send(MessageService.SendCommand cmd) {
        if (nextSendFailure != null) {
            RuntimeException failure = nextSendFailure;
            nextSendFailure = null;
            throw failure;
        }
        if (cmd.msgType() == MessageType.SYSTEM && !conversations.isMember(cmd.convId(), cmd.senderId())) {
            // 与真实 MessageService.checkSendPermission 同一条前置：SYSTEM 消息的作者也必须是成员。
            // 少了它，「退群是先删成员行还是先写通知」就变成一个测不出来的顺序——
            // 而先删后写会让那条通知永远丢失（真实实现只会记一条 ERROR，请求照样成功）。
            throw new TmException(ErrorCode.NOT_A_MEMBER,
                    "SYSTEM 消息的作者必须是会话成员: convId=" + cmd.convId());
        }
        sent.add(cmd);
        Message message = new Message();
        message.setId(800_000_000_000_000_000L);
        message.setConvId(cmd.convId());
        message.setSeq(1L);
        message.setSenderId(cmd.senderId());
        message.setMsgType(cmd.msgType());
        message.setContent(cmd.contentJson());
        return new MessageService.SendOutcome(message, false, 0);
    }

    @Override
    public long markRead(long convId, long actorId, long lastReadSeq) {
        markReadCalls++;
        if (!conversations.isMember(convId, actorId)) {
            throw new TmException(ErrorCode.NOT_A_MEMBER, "convId=" + convId);
        }
        conversations.updateLastReadSeq(convId, actorId, lastReadSeq);
        return lastReadSeq;
    }

    @Override
    public MessageService.SyncOutcome sync(MessageService.SyncCommand cmd) {
        throw new UnsupportedOperationException("会话 REST 用例不走 SYNC");
    }
}
