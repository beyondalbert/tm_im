package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.Friendship;
import com.tm.im.domain.enums.FriendshipStatus;
import com.tm.im.domain.repository.FriendshipRepository;
import com.tm.im.domain.support.PairKeys;
import com.tm.im.storage.mapper.FriendshipMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 好友关系仓储实现。
 *
 * <p><b>所有入参都过 {@link PairKeys#ordered}</b>。这不是多余的防御：
 * 关系以无序对存储（约定 {@code actor_a < actor_b}），若某处传反，
 * 查询会安静地查不到 → 返回「不是好友」→ 表现为「明明是好友却发不出消息」。
 * 归一化只做一次、只在这里做，是让这类 bug 无处容身的最低成本办法。
 *
 * <p><b>列表类方法两个方向各查一次再合并</b>：同一个人的关系行散在两个不同的索引前缀下
 * （{@code actor_a} 走主键前缀 {@code (actor_a, actor_b)}，{@code actor_b} 走 {@code idx_b}）。
 * 用 {@code OR} 写成一个查询也行，但那样 MySQL 多半会放弃索引做全表扫描——
 * 而这张表的规模是「用户数 × 好友数」。
 */
@Repository
public class FriendshipRepositoryImpl implements FriendshipRepository {

    private static final Logger log = LoggerFactory.getLogger(FriendshipRepositoryImpl.class);

    private final FriendshipMapper mapper;

    public FriendshipRepositoryImpl(FriendshipMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Friendship> find(long actorX, long actorY) {
        long[] p = PairKeys.ordered(actorX, actorY);
        return Optional.ofNullable(mapper.selectOne(Wrappers.<Friendship>lambdaQuery()
                .eq(Friendship::getActorA, p[0])
                .eq(Friendship::getActorB, p[1])
                .last("LIMIT 1")));
    }

    /**
     * 按请求 id 查。
     *
     * <p>它是 {@code uk_request_id} 唯一索引上的点查——这个索引不是可选的：
     * accept/reject 的入口参数就是 {@code request_id}，没有它每次操作都会退化成
     * 「全表按 request_id 扫描」，而那张表的规模是「用户数 × 好友数」。
     */
    @Override
    public Optional<Friendship> findByRequestId(long requestId) {
        if (requestId <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<Friendship>lambdaQuery()
                .eq(Friendship::getRequestId, requestId)
                .last("LIMIT 1")));
    }

    @Override
    public boolean areFriends(long actorX, long actorY) {
        if (PairKeys.isSelf(actorX, actorY)) {
            return false;
        }
        return find(actorX, actorY)
                .map(f -> f.getStatus() == FriendshipStatus.ACCEPTED)
                .orElse(false);
    }

    /**
     * 写入或更新。
     *
     * <p><b>{@code request_id} 缺失直接抛</b>（而不是自己造一个）：它是对外契约的一部分
     * （客户端拿它 accept/reject），服务端在生成它的那一刻才知道这次请求的语义。
     * 反过来让仓储「顺手补一个」会让「响应里的 request_id」与「库里的 request_id」
     * 变成两个来路不同的值——而唯一的失败形态是客户端拿着一个查不到的 id 去 accept，
     * 报 40400，看起来像请求过期了。
     *
     * <p><b>更新分支会把这一行整个换成入参描述的那个状态</b>
     * （{@code request_id} / {@code created_at} / {@code expires_at} / {@code message} / {@code status}）。
     * 因为调用它的有两种场景，而它们要的东西不同：
     * <ul>
     *   <li><b>重新发起</b>（旧的待处理请求已过期）：这是一个<b>新请求</b>，
     *       所以 request_id 与 created_at 都该换成新的；</li>
     *   <li><b>状态变更</b>（同意 / 拒绝 / 拉黑）：调用方传回来的就是库里现有那一行的
     *       request_id 与 created_at，所以对它们而言这几个字段自然不变。</li>
     * </ul>
     * 「更新时一律不动 request_id」看起来更安全，实际是错的：过期后重新发起时，
     * 响应会给客户端一个<b>库里不存在</b>的新 id，而用它去 accept 却也能成功
     * （因为按老 id 也能查到那一行）——两边各自“能用”，只是它们说的不是同一个请求。
     */
    @Override
    @Transactional
    public void save(Friendship friendship) {
        long[] p = PairKeys.ordered(friendship.getActorA(), friendship.getActorB());
        friendship.setActorA(p[0]);
        friendship.setActorB(p[1]);
        if (friendship.getRequestId() == null) {
            throw new IllegalArgumentException(
                    "好友关系缺少 request_id（必须由调用方生成，见接口注释）");
        }
        LocalDateTime now = LocalDateTime.now();
        if (friendship.getCreatedAt() == null) {
            log.warn("好友关系 created_at 缺失，回退到 JVM 默认时区取值 requestId={}，"
                    + "容器里（TZ=UTC）这会与 tm.time.zone 的口径不一致", friendship.getRequestId());
            friendship.setCreatedAt(now);
        }
        if (friendship.getExpiresAt() == null) {
            // 非 PENDING 的行不需要过期时间，但列是 NOT NULL。
            // 填 createdAt 而不是 NULL：一处「有值但无意义」比到处判空更容易解释。
            friendship.setExpiresAt(friendship.getCreatedAt());
        }
        friendship.setUpdatedAt(now);

        Optional<Friendship> existing = find(p[0], p[1]);
        if (existing.isPresent()) {
            update(p, friendship);
            return;
        }
        try {
            mapper.insert(friendship);
        } catch (DuplicateKeyException e) {
            // 并发发起：另一方已插入，退化为更新即可（幂等）
            update(p, friendship);
        }
    }

    @Override
    public void updateStatus(long actorX, long actorY, FriendshipStatus status) {
        long[] p = PairKeys.ordered(actorX, actorY);
        mapper.update(null, Wrappers.<Friendship>lambdaUpdate()
                .eq(Friendship::getActorA, p[0])
                .eq(Friendship::getActorB, p[1])
                .set(Friendship::getStatus, status)
                .set(Friendship::getUpdatedAt, LocalDateTime.now()));
    }

    @Override
    public boolean delete(long actorX, long actorY) {
        long[] p = PairKeys.ordered(actorX, actorY);
        return mapper.delete(Wrappers.<Friendship>lambdaQuery()
                .eq(Friendship::getActorA, p[0])
                .eq(Friendship::getActorB, p[1])) > 0;
    }

    /**
     * 列出好友 ID。
     *
     * <p>必须<b>双向查两遍</b>：关系只存一行，好友可能落在 {@code actor_a} 也可能落在
     * {@code actor_b}。这是「无序对只存一份」这个设计带来的唯一代价——
     * 换来的是不会出现 A→B 与 B→A 两条记录状态不一致的情况。
     */
    @Override
    public List<Long> listFriendIds(long actorId, int limit) {
        int cap = Math.max(1, limit);

        List<Long> asA = mapper.selectList(Wrappers.<Friendship>lambdaQuery()
                        .eq(Friendship::getActorA, actorId)
                        .eq(Friendship::getStatus, FriendshipStatus.ACCEPTED)
                        .orderByDesc(Friendship::getUpdatedAt)
                        .last("LIMIT " + cap))
                .stream().map(Friendship::getActorB).toList();

        List<Long> asB = mapper.selectList(Wrappers.<Friendship>lambdaQuery()
                        .eq(Friendship::getActorB, actorId)
                        .eq(Friendship::getStatus, FriendshipStatus.ACCEPTED)
                        .orderByDesc(Friendship::getUpdatedAt)
                        .last("LIMIT " + cap))
                .stream().map(Friendship::getActorA).toList();

        List<Long> all = new ArrayList<>(asA.size() + asB.size());
        all.addAll(asA);
        all.addAll(asB);
        return all.stream().distinct().limit(cap).toList();
    }

    @Override
    public List<Friendship> pageFriends(long actorId, Cursor cursor, int limit) {
        int cap = Math.max(1, limit);
        List<Friendship> merged = new ArrayList<>();
        merged.addAll(select(Wrappers.<Friendship>lambdaQuery()
                .eq(Friendship::getActorA, actorId)
                .eq(Friendship::getStatus, FriendshipStatus.ACCEPTED)
                .apply(cursorWhere(cursor)), cap));
        merged.addAll(select(Wrappers.<Friendship>lambdaQuery()
                .eq(Friendship::getActorB, actorId)
                .eq(Friendship::getStatus, FriendshipStatus.ACCEPTED)
                .apply(cursorWhere(cursor)), cap));
        return sorted(merged);
    }

    @Override
    public List<Friendship> pageRequests(long actorId, FriendshipStatus status, Boolean initiatedByMe,
                                         Cursor cursor, int limit) {
        int cap = Math.max(1, limit);
        List<Friendship> merged = new ArrayList<>();
        for (boolean asA : new boolean[]{true, false}) {
            LambdaQueryWrapper<Friendship> wrapper = Wrappers.<Friendship>lambdaQuery()
                    .eq(asA ? Friendship::getActorA : Friendship::getActorB, actorId)
                    .apply(cursorWhere(cursor));
            if (status != null) {
                wrapper.eq(Friendship::getStatus, status);
            }
            if (initiatedByMe != null) {
                // 方向不等于状态：这两个参数一起才能表达「我收到的待处理请求」。
                // 用 actorId 比而不是存一个「我是发起人」的空值：initiator 是 NOT NULL 的列。
                if (initiatedByMe) {
                    wrapper.eq(Friendship::getInitiator, actorId);
                } else {
                    wrapper.ne(Friendship::getInitiator, actorId);
                }
            }
            merged.addAll(select(wrapper, cap));
        }
        return sorted(merged);
    }

    @Override
    public int countInitiatedSince(long actorId, LocalDateTime since) {
        return Math.toIntExact(mapper.selectCount(Wrappers.<Friendship>lambdaQuery()
                .eq(Friendship::getInitiator, actorId)
                .ge(Friendship::getCreatedAt, since)));
    }

    // ------------------------------------------------------------------ 内部

    private void update(long[] pair, Friendship friendship) {
        mapper.update(null, Wrappers.<Friendship>lambdaUpdate()
                .eq(Friendship::getActorA, pair[0])
                .eq(Friendship::getActorB, pair[1])
                // 这一行整个换成入参描述的状态（含 request_id / created_at）：
                // 「过期的请求被重新发起」是一个新请求，见 save 的注释。
                .set(Friendship::getRequestId, friendship.getRequestId())
                .set(Friendship::getCreatedAt, friendship.getCreatedAt())
                .set(Friendship::getStatus, friendship.getStatus())
                .set(Friendship::getInitiator, friendship.getInitiator())
                .set(Friendship::getMessage, friendship.getMessage())
                .set(Friendship::getExpiresAt, friendship.getExpiresAt())
                .set(Friendship::getUpdatedAt, friendship.getUpdatedAt()));
    }

    /** 取最新的 cap 行（排序与 LIMIT 一起构成「最新的一批」；缺了排序就是随机丢行）。 */
    private List<Friendship> select(LambdaQueryWrapper<Friendship> wrapper, int cap) {
        wrapper.orderByDesc(Friendship::getUpdatedAt)
                .orderByDesc(Friendship::getRequestId)
                .last("LIMIT " + cap);
        return mapper.selectList(wrapper);
    }

    /**
     * 游标的 SQL 谓词 —— <b>严格「比游标更旧」</b>，而不是「不等于游标」：
     * 后者会让同一毫秒里那些已经下发过的行再次出现（客户端看到重复的人）。
     *
     * <p>用 {@code apply} 把整段谓词连同括号一起塞进去，而不是用
     * {@code lt(...).or(...)} 拼 Wrapper：括号必须真实存在。一旦少了那层括号，
     * {@code AND (a OR b)} 会变成 {@code AND a OR b}，语义成了「满足 a 的全部，或者满足 b 的全部」——
     * 一个看起来完全正常、只是多返回一批数据的查询。
     *
     * <p>拼接的是 {@code LocalDateTime} 与 {@code long}（不是用户输入），
     * 所以不存在注入面；时间用 ISO 形式（{@code LocalDateTime.toString()}），
     * 它是 MySQL 的 {@code DATETIME(3)} 能直接解析的字面量。
     */
    private static String cursorWhere(Cursor cursor) {
        if (cursor == null) {
            return "1 = 1";
        }
        String at = cursor.updatedAt().toString();
        return "(updated_at < '" + at + "' OR (updated_at = '" + at
                + "' AND request_id < " + cursor.requestId() + "))";
    }

    /** 合并后的全局序：{@code updated_at DESC, request_id DESC}（与游标键完全一致）。 */
    private static List<Friendship> sorted(List<Friendship> rows) {
        rows.sort((x, y) -> {
            int byTime = y.getUpdatedAt().compareTo(x.getUpdatedAt());
            return byTime != 0 ? byTime : Long.compare(y.getRequestId(), x.getRequestId());
        });
        return rows;
    }
}
