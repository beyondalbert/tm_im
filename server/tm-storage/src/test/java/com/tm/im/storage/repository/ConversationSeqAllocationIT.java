package com.tm.im.storage.repository;

import com.tm.im.domain.entity.Conversation;
import com.tm.im.domain.enums.ConvType;
import com.tm.im.domain.repository.ConversationRepository;
import com.tm.im.storage.it.ItSpringConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话序号分配的真实服务验证（MySQL + Redis 都是真的）。
 *
 * <p><b>为什么必须用真服务</b>：序号这件事的失败形态全是「并发下偶发」——
 * 单测里用假 Redis、假 Mapper，验证的只是我自己写的那套假实现。
 * 而这里要证的恰恰是两件只有在真实并发里才会暴露的事：
 * <ol>
 *   <li>{@code Redis INCR} 的原子性与「每号唯一」；</li>
 *   <li>兜底路径（数据库计数器）在<b>事务真正生效</b>时才能靠行锁串行化。</li>
 * </ol>
 * 第 2 条是本类的重点：它曾经是个真缺陷 —— {@code nextSeq} 自调用同类的
 * {@code nextSeqFromDb}，而自调用不走 Spring 代理，{@code @Transactional} 形同虚设，
 * 于是 UPDATE 与 SELECT 各自自动提交，行锁在两条语句之间就释放了。
 * 表现是并发取号出现<b>重复</b>，而重复序号的后果是消息插不进 {@code PRIMARY (conv_id, seq)}，
 * 客户端看到的是「发出去没反应」。这条路径只在 Redis 抖动时才会走到，
 * 靠人工是碰不上的，只能靠本类这样的测试固定住。
 *
 * <p><b>数据清理</b>：测试会往真实库里写 conversation 行、往 Redis 写
 * {@code tm:seq:*}。每张表都按本次运行生成的 id 精确删除，
 * 不做 TRUNCATE / FLUSHDB —— 这是开发库不是我的库。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ItSpringConfig.class)
class ConversationSeqAllocationIT {

    /** 本次运行的会话 id 前缀：与库里既有数据天然不重叠（Snowflake 量级 + 运行序号）。 */
    private static final AtomicLong ID_SEQ = new AtomicLong(System.nanoTime() * 1000L);

    private static final int THREADS = 8;
    private static final int PER_THREAD = 25;

    @Autowired
    @Qualifier("conversationRepositoryImpl")
    private ConversationRepository repository;

    @Autowired
    @Qualifier("conversationRepositoryWithoutRedis")
    private ConversationRepository fallbackRepository;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private DataSource dataSource;

    /** 建过的会话，测试结束逐个删除。 */
    private final List<Long> created = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void cleanUp() {
        synchronized (created) {
            for (Long id : created) {
                redis.delete(ConversationRepositoryImpl.SEQ_KEY_PREFIX + id);
                deleteConversation(id);
            }
            created.clear();
        }
    }

    /**
     * 直接删行，<b>不经被测仓储</b>：清理不该依赖被测代码是否正常
     * （被测代码写错的时候，清理也跟着失效会把脏数据留在库里）。
     * 这条 SQL 仍走 ShardingSphere 逻辑表，与生产同一条路径。
     */
    private void deleteConversation(long id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM conversation WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理测试会话失败: id=" + id, e);
        }
    }

    /** 新建一个一次性会话（GROUP 无 pair_key 唯一键冲突，纯探针）。 */
    private long newConversation() {
        Conversation c = new Conversation();
        c.setId(ID_SEQ.incrementAndGet());
        c.setConvType(ConvType.GROUP);
        c.setTitle("it-seq-probe");
        c.setCreatedAt(LocalDateTime.now());
        repository.insert(c);
        created.add(c.getId());
        return c.getId();
    }

    /** 并发调用 {@link ConversationRepository#nextSeq}，返回所有取到的号。 */
    private List<Long> allocateConcurrently(ConversationRepository repo, long convId)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Long> got = Collections.synchronizedList(new ArrayList<>());
        // 让所有线程尽量同时开跑：先各自就位，再一起放行。
        // 不这么做的话，第一个线程可能已经跑完 25 次，后面的才刚开始，
        // 测试就退化成串行，永远发现不了锁粒度问题。
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        try {
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < PER_THREAD; i++) {
                            got.add(repo.nextSeq(convId));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS))
                    .as("并发取号应当在 120 秒内跑完（超时通常意味着行锁互相等待）")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(got).as("并发取号不能吞异常").hasSize(THREADS * PER_THREAD);
        return got;
    }

    @Test
    @DisplayName("M3 验收：Redis 路径并发取号不重复，且恰好用满 1..N")
    void redisPathAllocatesUniqueSeqUnderConcurrency() throws Exception {
        long convId = newConversation();

        List<Long> got = allocateConcurrently(repository, convId);

        Set<Long> unique = new HashSet<>(got);
        assertThat(unique)
                .as("重复序号会让第二条消息插不进 (conv_id, seq) 主键 —— 这是最要命的失败")
                .hasSize(got.size());
        assertThat(unique).as("空键上的 INCR 应当给出连续的 1..%d", got.size())
                .containsExactlyInAnyOrderElementsOf(contiguous(got.size()));

        // 同时核对 Redis 侧的真实值：应用看到的号必须是 Redis 里那个号，
        // 否则"唯一"只是应用内存里的巧合。
        String v = redis.opsForValue().get(ConversationRepositoryImpl.SEQ_KEY_PREFIX + convId);
        assertThat(v).as("Redis 计数器最终应等于已发出的最大号").isEqualTo(String.valueOf(got.size()));
    }

    @Test
    @DisplayName("Redis 被清空后计数器会从头开始 —— 这正是自愈（raiseSeqFloor）存在的理由")
    void flushedRedisRestartsNumberingAndSelfHealFixesIt() {
        long convId = newConversation();

        for (int i = 1; i <= 5; i++) {
            assertThat(repository.nextSeq(convId)).as("第 %d 次取号", i).isEqualTo(i);
        }

        // 模拟 Redis 被清空 / 无持久化重启 / 故障切换到空实例
        redis.delete(ConversationRepositoryImpl.SEQ_KEY_PREFIX + convId);

        long afterFlush = repository.nextSeq(convId);
        assertThat(afterFlush)
                .as("清空后确实从 1 重新开始 —— 也就是说这一刻起，序号一定会与库里已有消息相撞。"
                        + "调用方（MessageService）必须靠 (conv_id,seq) 主键发现碰撞并自愈，"
                        + "否则表现为「发出去没反应」")
                .isEqualTo(1);

        // 自愈：把序号源抬到库内已有的最大 seq 之上
        repository.raiseSeqFloor(convId, 5);
        assertThat(repository.nextSeq(convId))
                .as("自愈后必须从库里最大 seq 之上第一个号继续（5 → 6），"
                        + "而不是白吃一个号或继续从 1 发")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("自愈只抬不降：过期的 floor 不能把计数器往回拉")
    void raiseSeqFloorNeverLowersTheCounter() {
        long convId = newConversation();

        long v1 = repository.nextSeq(convId);
        long v2 = repository.nextSeq(convId);
        assertThat(v2).isEqualTo(v1 + 1);

        // 场景：并发调用自愈时，某个调用方拿的是过期的「库内最大 seq」。
        // 若实现是「无条件 SET floor」，它会把已经发出去的号重置回去，
        // 下一次取号就撞主键 —— 修 bug 的动作本身制造 bug。
        repository.raiseSeqFloor(convId, 1);

        assertThat(repository.nextSeq(convId))
                .as("floor=1 低于当前值，必须被忽略，也不应当白吃一个号")
                .isEqualTo(v2 + 1);
    }

    @Test
    @DisplayName("兜底路径从 floor 之上继续：seq_counter 长期没写过也不会发出旧号")
    void dbCounterContinuesAboveFloor() {
        long convId = newConversation();

        // 库里已有 42 条消息，而 conversation.seq_counter 还是初始的 0
        // （走 Redis 正常路径时这个计数器根本不会被更新）
        assertThat(repository.nextSeqFromDb(convId, 42L)).isEqualTo(43L);
        assertThat(repository.nextSeqFromDb(convId, 42L))
                .as("后续取号应当继续递增，而不是每次都从 floor 重算")
                .isEqualTo(44L);
    }

    @Test
    @DisplayName("兜底路径（Redis 不可用）在并发下同样不重复 —— 依赖 @Transactional 真的生效")
    void fallbackPathIsUniqueUnderConcurrency() throws Exception {
        long convId = newConversation();

        List<Long> got = allocateConcurrently(fallbackRepository, convId);

        Set<Long> unique = new HashSet<>(got);
        assertThat(unique)
                .as("数据库计数器的并发安全完全依赖 UPDATE 拿到的行锁持续到 SELECT 之后。"
                        + "若 @Transactional 因同类自调用而失效，两条语句各自提交，"
                        + "多个线程会读到同一个值 —— 这条断言会失败，且必须失败")
                .hasSize(got.size());
        assertThat(unique).containsExactlyInAnyOrderElementsOf(contiguous(got.size()));
    }

    private static List<Long> contiguous(int n) {
        List<Long> expected = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            expected.add((long) i);
        }
        return expected;
    }
}
