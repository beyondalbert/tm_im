package com.tm.im.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体映射约束测试。
 *
 * <p>重点是那条<b>刻意保留的告警</b>：联合主键表不标 {@code @TableId}。
 * 这个选择看起来像「没配好」，很容易被后来的人当成缺陷「修掉」——
 * 而修掉的后果是 {@code selectById} 在分片表上返回任意一行且不报错。
 * 所以用测试把它钉住。
 */
class EntityAnnotationTest {

    /** 联合主键表：MyBatis-Plus 不支持，必须不标 @TableId。 */
    private static final List<Class<?>> COMPOSITE_PK_ENTITIES = List.of(
            Message.class,          // (conv_id, seq)
            ConversationMember.class, // (conv_id, actor_id)
            ActorSecret.class,      // (actor_id, secret_type)
            Friendship.class,       // (actor_a, actor_b)
            FeedItem.class          // (owner_id, score, post_id)
    );

    /** 单列主键表：必须标 @TableId(INPUT)，因为主键由应用层 Snowflake 生成。 */
    private static final List<Class<?>> SINGLE_PK_ENTITIES = List.of(
            Actor.class, AgentProfile.class, Conversation.class, Post.class, Media.class
    );

    private static long countAnnotatedTableIds(Class<?> type) {
        long n = 0;
        for (var f : type.getDeclaredFields()) {
            if (f.isAnnotationPresent(TableId.class)) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("联合主键表一律不标 @TableId——标了会让 selectById 静默返回任意一行")
    void compositePkEntitiesHaveNoTableId() {
        for (Class<?> type : COMPOSITE_PK_ENTITIES) {
            assertThat(countAnnotatedTableIds(type))
                    .as("%s 是联合主键表。若为其标注 @TableId，MyBatis-Plus 生成的 selectById "
                            + "会退化成按单列过滤，在分片表上返回多行中的任意一行且不报错。"
                            + "启动时的那条告警是刻意保留的，不要用 @TableId 去消除它。", type.getSimpleName())
                    .isZero();
        }
    }

    @Test
    @DisplayName("单列主键表标 @TableId 且类型为 INPUT（主键来自 Snowflake，不是数据库自增）")
    void singlePkEntitiesUseInputStrategy() {
        for (Class<?> type : SINGLE_PK_ENTITIES) {
            assertThat(countAnnotatedTableIds(type))
                    .as("%s 应恰好有一个 @TableId", type.getSimpleName())
                    .isEqualTo(1);
            for (var f : type.getDeclaredFields()) {
                TableId id = f.getAnnotation(TableId.class);
                if (id != null) {
                    assertThat(id.type())
                            .as("%s 的主键策略必须是 INPUT。用 ASSIGN_ID 会绕过我们自己的 "
                                    + "Snowflake（节点号可控、可解析出生成时间），用 AUTO 则会被 ShardingSphere 拦下",
                                    type.getSimpleName())
                            .isEqualTo(IdType.INPUT);
                    assertThat(id.value()).as("主键列名不能空着").isNotBlank();
                }
            }
        }
    }

    @Test
    @DisplayName("逻辑表名：分片表是 message，不是 message_N")
    void logicalTableNames() {
        assertThat(Message.class.getAnnotation(TableName.class).value())
                .as("实体必须指向逻辑表 message；写 message_0 会让 ShardingSphere 失去分片能力，"
                        + "所有消息挤进同一张物理表")
                .isEqualTo("message");
        assertThat(Actor.class.getAnnotation(TableName.class).value()).isEqualTo("actor");
        assertThat(Conversation.class.getAnnotation(TableName.class).value()).isEqualTo("conversation");
    }

    @Test
    @DisplayName("toString 必须脱敏凭据字段——实体被随手打进日志是常态")
    void toStringRedactsSecrets() {
        ActorSecret secret = new ActorSecret();
        secret.setActorId(1L);
        secret.setSecretHash("$2a$10$SUPER_SECRET_HASH_VALUE");

        String text = secret.toString();
        assertThat(text)
                .as("哈希值出现在日志里等于凭据泄露")
                .doesNotContain("SUPER_SECRET_HASH_VALUE");
        assertThat(text).contains("secretHash=***");
        assertThat(text).as("非敏感字段仍要保留，否则日志失去排障价值").contains("actorId=1");
    }

    @Test
    @DisplayName("普通实体的 toString 不脱敏业务字段（否则排障时看不到内容）")
    void toStringKeepsBusinessFields() {
        Message m = new Message();
        m.setConvId(100L);
        m.setSeq(7L);
        m.setContent("{\"text\":\"hi\"}");

        assertThat(m.toString())
                .contains("convId=100")
                .contains("seq=7")
                .contains("hi");
    }
}
