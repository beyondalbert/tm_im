package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.Conversation;
import com.tm.im.storage.mapper.ConversationMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据库序号计数器（{@code conversation.seq_counter}）—— Redis 不可用时的兜底取号。
 *
 * <p><b>为什么单独一个类</b>：取号必须由「一条 UPDATE + 一条 SELECT」在
 * <b>同一个事务</b>里完成，而 Spring 的 {@code @Transactional} 是代理生效的——
 * 同类内部自调用（{@code nextSeq} → {@code nextSeqFromDb}）会绕过代理。
 * 那样两条语句各自自动提交，UPDATE 拿到的行锁在提交瞬间就释放，
 * 两个并发调用会取到<b>同一个序号</b>：都自增成功、都读到后写者的值。
 * 序号重复的后果是消息插不进去（主键 {@code (conv_id, seq)}），
 * 而不是「顺序乱了」——所以这个事务边界不是洁癖，是正确性。
 *
 * <p>拆成独立 bean 后，无论从哪个入口调用，事务都由 Spring 代理真正开启。
 */
@Component
public class ConversationSeqCounter {

    private final ConversationMapper conversationMapper;

    public ConversationSeqCounter(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    /**
     * 把计数器推进到 {@code max(seq_counter, floor) + 1} 并返回新值；并发调用在行锁上串行。
     *
     * <p>为什么更新语句里就要带上 {@code floor}：走 Redis 正常路径时
     * {@code seq_counter} 根本不会被写，它可能远远落后于库内实际最大 seq（甚至是 0）。
     * 若只写 {@code seq_counter + 1}，兜底路径一启动就会发出早已被占用的序号。
     *
     * <p>为什么读取用 {@code SELECT ... FOR UPDATE}：REPEATABLE READ 下普通 SELECT
     * 是快照读，理论上可能读到本事务开始前的旧版本；锁定读永远读最新已提交值。
     * 序号这种事不允许「理论上可能」。
     *
     * @param floor 库内该会话已有的最大 seq（无历史消息传 0）
     */
    @Transactional
    public long bumpAndRead(long convId, long floor) {
        long safeFloor = Math.max(floor, 0L);
        conversationMapper.update(null, Wrappers.<Conversation>lambdaUpdate()
                .eq(Conversation::getId, convId)
                .setSql("seq_counter = GREATEST(seq_counter, " + safeFloor + ") + 1"));
        Conversation c = conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getId, convId)
                .last("FOR UPDATE"));
        if (c == null) {
            throw new IllegalStateException("会话不存在，无法分配序号: convId=" + convId);
        }
        return c.getSeqCounter() == null ? 0L : c.getSeqCounter();
    }
}
