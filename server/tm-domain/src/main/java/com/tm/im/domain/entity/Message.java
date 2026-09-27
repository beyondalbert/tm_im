// 源: deploy/sql/01-schema.sql  sha256[:16]=7818dd686897f64d
package com.tm.im.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tm.im.domain.enums.MessageType;

import java.time.LocalDateTime;

/**
 * 消息分片表（逻辑表 message → 物理表 message_0..15）
 *
 * <p>由 <code>tools/gen_entities.py</code> 从 <code>deploy/sql/01-schema.sql</code> 生成，
 * 请勿手工编辑——改结构请改 DDL 生成器后重跑。
 *
 * <p><b>本表是联合主键（conv_id, seq），故刻意不标注 {@code @TableId}。</b>
 * MyBatis-Plus 不支持联合主键；若把其中一列强标为 {@code @TableId}，
 * 它生成的 {@code selectById} 会退化成 {@code WHERE conv_id = ?}，
 * 在分片表上返回多行中的任意一行且不报错。这类静默错误比一条启动告警危险得多，
 * 因此选择让它告警。本表必须用显式条件查询。
 */
@TableName("message")
public class Message {

    /** Snowflake */
    private Long id;

    /** ★分片键 */
    // 联合主键之一，见类注释：故意不标 @TableId
    private Long convId;

    /** 会话内严格递增 */
    // 联合主键之一，见类注释：故意不标 @TableId
    private Long seq;

    private Long senderId;

    /** 1=TEXT 2=IMAGE 3=SYSTEM */
    private MessageType msgType;

    private String content;

    private Long replyTo;

    /** 幂等键 */
    private String clientMsgId;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConvId() {
        return convId;
    }

    public void setConvId(Long convId) {
        this.convId = convId;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public MessageType getMsgType() {
        return msgType;
    }

    public void setMsgType(MessageType msgType) {
        this.msgType = msgType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(Long replyTo) {
        this.replyTo = replyTo;
    }

    public String getClientMsgId() {
        return clientMsgId;
    }

    public void setClientMsgId(String clientMsgId) {
        this.clientMsgId = clientMsgId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * 凭据类字段（secret / hash / token / password）一律脱敏。
     * 实体被随手打进日志是极常见的事，脱敏只有放在这里才拦得住。
     */
    @Override
    public String toString() {
        return "Message{" +
                "id=" + id + ", " +
                "convId=" + convId + ", " +
                "seq=" + seq + ", " +
                "senderId=" + senderId + ", " +
                "msgType=" + msgType + ", " +
                "content=" + content + ", " +
                "replyTo=" + replyTo + ", " +
                "clientMsgId=" + clientMsgId + ", " +
                "createdAt=" + createdAt
                + '}';
    }
}
