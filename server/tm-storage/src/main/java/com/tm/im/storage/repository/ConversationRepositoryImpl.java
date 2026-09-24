package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.Conversation;
import com.tm.im.domain.entity.ConversationMember;
import com.tm.im.domain.enums.MemberRole;
import com.tm.im.domain.repository.ConversationRepository;
import com.tm.im.storage.mapper.ConversationMapper;
import com.tm.im.storage.mapper.ConversationMemberMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ConversationRepositoryImpl implements ConversationRepository {

    private static final Logger log = LoggerFactory.getLogger(ConversationRepositoryImpl.class);

    /** 与 DESIGN §10.3 的 key 设计一致：{@code tm:seq:{convId}}。 */
    static final String SEQ_KEY_PREFIX = "tm:seq:";

    /**
     * 「不值则抬」—— <b>一条 Lua 保证原子</b>，且<b>不消耗号</b>。
     *
     * <p>为什么不能拆成 {@code GET → SET} 两步：Redis 数据被清空后多个并发发送
     * 会同时看到「当前值小于基线」，各自的 SET 互相覆盖 —— 于是又一次撞主键。
     * 虽然走到这里已经是异常路径，但恰恰是并发最容易出事的时刻。
     * Lua 脚本在 Redis 内部串行执行，不存在这个窗口。
     *
     * <p>判据是 {@code cur < floor} 而不是 {@code cur <= floor}：
     * {@code cur == floor} 时值已经对了，不需要写。
     * 而<b>无条件 SET</b> 的写法更危险：并发自愈里拿旧基线的那个调用
     * 会把已经发出去的号拉回来，修 bug 的动作本身制造 bug。
     *
     * <p><b>为什么不能顺手 INCR</b>（写 `SET floor` 再 `INCR` 返回）：
     * 那样自愈会白吃一个号，而号是会被写进消息表主键的离散量。
     * 调用方的语义应当满足「自愈之后从 floor 之上第一个号继续」，
     * 多跳一个号虽然无害，但会让「为什么少了一个号」变成一个永远没人能回答的问题。
     * 返回值只用于日志（旧值 → 是否抬升）。
     */
    private static final RedisScript<Long> RAISE_FLOOR_IF_LOW = new DefaultRedisScript<>(
            "local cur = tonumber(redis.call('GET', KEYS[1]) or '0')\n"
                    + "local floor = tonumber(ARGV[1])\n"
                    + "if cur < floor then redis.call('SET', KEYS[1], floor) end\n"
                    + "return cur",
            Long.class);

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper memberMapper;

    /** DB 兜底取号：单独 bean，保证「UPDATE + SELECT」在同一事务里（见该类注释）。 */
    private final ConversationSeqCounter dbCounter;

    /**
     * 允许为 {@code null}：单测与「没配 Redis」的部署要能跑通。
     * 不是「优雅降级的美化」，而是<b>必须</b>——序号是消息能否落库的前提，
     * 一条 Redis 连接抖动不应让整个聊天功能不可用（见 {@link #nextSeqFromDb}）。
     */
    private final StringRedisTemplate redis;

    @Autowired
    public ConversationRepositoryImpl(ConversationMapper conversationMapper,
                                     ConversationMemberMapper memberMapper,
                                     ConversationSeqCounter dbCounter,
                                     StringRedisTemplate redis) {
        this.conversationMapper = conversationMapper;
        this.memberMapper = memberMapper;
        this.dbCounter = dbCounter;
        this.redis = redis;
    }

    /** 便于单测/无 Redis 部署构造。 */
    public ConversationRepositoryImpl(ConversationMapper conversationMapper,
                                     ConversationMemberMapper memberMapper,
                                     ConversationSeqCounter dbCounter) {
        this(conversationMapper, memberMapper, dbCounter, null);
    }

    @Override
    public Optional<Conversation> findById(long convId) {
        return Optional.ofNullable(conversationMapper.selectById(convId));
    }

    @Override
    public Optional<Conversation> findDirectByPairKey(String pairKey) {
        if (pairKey == null || pairKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(conversationMapper.selectOne(
                Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getPairKey, pairKey)
                        .last("LIMIT 1")));
    }

    @Override
    @Transactional
    public Conversation insert(Conversation conversation) {
        if (conversation.getCreatedAt() == null) {
            conversation.setCreatedAt(LocalDateTime.now());
        }
        conversationMapper.insert(conversation);
        return conversation;
    }

    @Override
    @Transactional
    public boolean addMember(long convId, long actorId, MemberRole role) {
        ConversationMember member = new ConversationMember();
        member.setConvId(convId);
        member.setActorId(actorId);
        member.setRole(role);
        member.setLastReadSeq(0L);
        member.setMuted(false);
        member.setJoinedAt(LocalDateTime.now());
        try {
            memberMapper.insert(member);
            return true;
        } catch (DuplicateKeyException e) {
            // 已存在 → 幂等返回 false，让调用方可以安全重试「进群」操作
            return false;
        }
    }

    @Override
    public void updateLastReadSeq(long convId, long actorId, long lastReadSeq) {
        // 已读游标只允许前进，不允许被迟到的请求回退。
        // 若不判断，客户端乱序到达的两次上报会让未读数凭空变大。
        memberMapper.update(null, Wrappers.<ConversationMember>lambdaUpdate()
                .eq(ConversationMember::getConvId, convId)
                .eq(ConversationMember::getActorId, actorId)
                .lt(ConversationMember::getLastReadSeq, lastReadSeq)
                .set(ConversationMember::getLastReadSeq, lastReadSeq));
    }

    @Override
    public Optional<ConversationMember> findMember(long convId, long actorId) {
        return Optional.ofNullable(memberMapper.selectOne(Wrappers.<ConversationMember>lambdaQuery()
                .eq(ConversationMember::getConvId, convId)
                .eq(ConversationMember::getActorId, actorId)
                .last("LIMIT 1")));
    }

    @Override
    public boolean isMember(long convId, long actorId) {
        return memberMapper.exists(Wrappers.<ConversationMember>lambdaQuery()
                .eq(ConversationMember::getConvId, convId)
                .eq(ConversationMember::getActorId, actorId));
    }

    @Override
    public List<Conversation> listByActor(long actorId, int limit) {
        List<ConversationMember> memberships = memberMapper.selectList(
                Wrappers.<ConversationMember>lambdaQuery()
                        .eq(ConversationMember::getActorId, actorId)
                        .orderByDesc(ConversationMember::getJoinedAt)
                        .last("LIMIT " + Math.max(1, limit)));
        if (memberships.isEmpty()) {
            return List.of();
        }
        List<Long> convIds = memberships.stream().map(ConversationMember::getConvId).toList();
        return conversationMapper.selectBatchIds(convIds);
    }

    @Override
    public List<Long> listMemberIds(long convId, int limit) {
        return memberMapper.selectList(Wrappers.<ConversationMember>lambdaQuery()
                        .eq(ConversationMember::getConvId, convId)
                        .last("LIMIT " + Math.max(1, limit)))
                .stream().map(ConversationMember::getActorId).toList();
    }

    /**
     * 分配会话内下一个序号：<b>优先 Redis INCR，Redis 不可用/未配置时落数据库计数器</b>。
     *
     * <p>热路径只有一趟 Redis 往返。这里刻意<b>不</b>先查库算基线：那会把
     * 「每条消息一次额外查询」加到全系统最热的写路径上，而且只有在 Redis
     * 刚被清空时才真正需要。取而代之的是「撞了再修」：
     * {@code MessageService} 插库时若撞 {@code PRIMARY (conv_id, seq)}，
     * 就用库内实际最大 seq 调 {@link #raiseSeqFloor} 再重试（详见该方法）。
     * 代价是 Redis 被清空后每个会话会浪费一次 INSERT，收益是正常路径零额外开销。
     */
    @Override
    public long nextSeq(long convId) {
        if (redis == null) {
            return nextSeqFromDb(convId, 0L);
        }
        try {
            Long v = redis.opsForValue().increment(SEQ_KEY_PREFIX + convId);
            if (v == null) {
                throw new IllegalStateException("Redis INCR 返回 null");
            }
            return v;
        } catch (RuntimeException e) {
            // 这里必须是 WARN 而不是 DEBUG：走到这条分支意味着已经退化为
            // 「每条消息一次数据库行更新」，吞吐会掉一个数量级，属于要人工介入的故障。
            log.warn("Redis 取序号失败，本次退化为数据库计数器 convId={}", convId, e);
            return nextSeqFromDb(convId, 0L);
        }
    }

    /**
     * 序号源自愈：把 Redis 计数与数据库计数器都抬到不低于 {@code floor}。
     *
     * <p>语义：调用之后，<b>下一次取号从 {@code max(当前值, floor) + 1} 开始</b>。
     * 自愈本身不消耗号（为什么不消耗见 Lua 脚本的注释）。
     *
     * <p><b>两处都抬</b>是刻意的。只抬 Redis 的话，下次 Redis 故障切到兜底路径，
     * 数据库计数器仍然停在旧值 → 又从头发号 → 又撞主键，变成「每次 Redis 故障
     * 都撞一轮」。只抬数据库则对当前正在服务的 Redis 路径毫无帮助。
     *
     * <p>数据库那边用 {@code GREATEST(seq_counter, floor)} 而不是直接赋值：
     * 计数器可能已经被并发地推得更高，直接赋 floor 会让它<b>回退</b>。
     */
    @Override
    public void raiseSeqFloor(long convId, long floor) {
        if (floor <= 0) {
            return;
        }
        if (redis != null) {
            try {
                Long before = redis.execute(RAISE_FLOOR_IF_LOW,
                        List.of(SEQ_KEY_PREFIX + convId), Long.toString(floor));
                log.warn("已抬升序号基线 convId={} floor={} Redis原值={}", convId, floor, before);
            } catch (RuntimeException e) {
                log.warn("自愈 Redis 序号基线失败 convId={} floor={}", convId, floor, e);
            }
        }
        conversationMapper.update(null, Wrappers.<Conversation>lambdaUpdate()
                .eq(Conversation::getId, convId)
                .setSql("seq_counter = GREATEST(seq_counter, " + floor + ")"));
        log.warn("已抬升数据库序号基线 convId={} floor={}（Redis 计数与/或数据库计数器曾落后于库内最大 seq）",
                convId, floor);
    }

    /**
     * 从数据库推进会话序号 —— <b>仅作为 Redis 不可用时的兜底</b>。
     *
     * <p>正常路径是 Redis {@code INCR tm:seq:{convId}}：一趟内存往返、天然原子。
     * 这里依赖 {@code UPDATE ... SET seq_counter = GREATEST(seq_counter, floor) + 1}
     * 拿到的行锁来串行化并发调用，代价是每次分配写一次磁盘页，
     * 吞吐远低于 Redis——所以它只是兜底，一旦被高频调用就说明 Redis 出问题了，
     * 应当告警而不是默默扛着（{@link #nextSeq} 里已打 WARN）。
     *
     * <p><b>为什么必须带 {@code floor}</b>：走 Redis 正常路径时
     * {@code seq_counter} 根本不会被更新，它可能远远落在库内实际最大 seq 之后
     * （甚至是 0）。此时直接自增就会发出早已被占用的序号，插入撞主键。
     *
     * <p>事务在 {@link ConversationSeqCounter#bumpAndRead} 里，不在本方法上：
     * 本方法会被同类内部的 {@link #nextSeq} 调用，自调用不走 Spring 代理，
     * 标在这里等于没有事务（原因详见那个类）。
     */
    @Override
    public long nextSeqFromDb(long convId, long floor) {
        return dbCounter.bumpAndRead(convId, floor);
    }
}
