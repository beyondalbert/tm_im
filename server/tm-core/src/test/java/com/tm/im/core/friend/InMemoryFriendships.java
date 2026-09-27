package com.tm.im.core.friend;

import com.tm.im.domain.entity.Friendship;
import com.tm.im.domain.enums.FriendshipStatus;
import com.tm.im.domain.repository.FriendshipRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存好友关系仓储替身。
 *
 * <p><b>它刻意把「无序对归一化」与「游标是严格更旧」两条也实现了</b>，
 * 而不是简单地按 {actor_a, actor_b} 元组存：这两条是被测规则的前提
 * （参数传反时查询会静默查不到），替身若宽容，测试就会在一个错误的前提下变绿。
 *
 * <p>但 SQL 层面的正确性（{@code 游标谓词的括号}、索引是否被用上、
 * 同一毫秒下的分页是否重复）它<b>证明不了</b>——那由 {@code FriendHttpIT}
 * 在真实 MySQL 上覆盖。这里只保证「业务规则」这一层。
 */
public class InMemoryFriendships implements FriendshipRepository {

    /** key = "min_max"。 */
    private final Map<String, Friendship> rows = new LinkedHashMap<>();

    /** 让「上次操作时间」可控：入库时间都会加上它（模拟历史数据、过期请求）。 */
    private final List<String> calls = new ArrayList<>();

    @Override
    public Optional<Friendship> find(long actorX, long actorY) {
        return Optional.ofNullable(rows.get(key(actorX, actorY)));
    }

    @Override
    public Optional<Friendship> findByRequestId(long requestId) {
        return rows.values().stream()
                .filter(row -> row.getRequestId() != null && row.getRequestId() == requestId)
                .findFirst();
    }

    @Override
    public boolean areFriends(long actorX, long actorY) {
        Friendship row = rows.get(key(actorX, actorY));
        return row != null && row.getStatus() == FriendshipStatus.ACCEPTED;
    }

    @Override
    public void save(Friendship friendship) {
        calls.add("save");
        if (friendship.getRequestId() == null) {
            throw new IllegalArgumentException("好友关系缺少 request_id");
        }
        long[] pair = ordered(friendship.getActorA(), friendship.getActorB());
        friendship.setActorA(pair[0]);
        friendship.setActorB(pair[1]);
        // 截到毫秒：库里的列是 DATETIME(3)，而 LocalDateTime.now() 带纳秒。
        // 不截的话，游标（毫秒）与行上的时间（纳秒）永远不相等，
        // 分页测试会以一个与生产无关的原因失败/通过。
        friendship.setUpdatedAt(millis(friendship.getUpdatedAt()));
        friendship.setCreatedAt(millis(friendship.getCreatedAt()));
        friendship.setExpiresAt(millis(friendship.getExpiresAt()));
        Friendship existing = rows.get(key(pair[0], pair[1]));
        if (existing == null) {
            rows.put(key(pair[0], pair[1]), friendship);
            return;
        }
        // 与真实实现同口径：整行换成入参描述的状态（含 request_id / created_at）——
        // 「过期的请求被重新发起」是一个新请求。
        existing.setRequestId(friendship.getRequestId());
        existing.setCreatedAt(friendship.getCreatedAt());
        existing.setStatus(friendship.getStatus());
        existing.setInitiator(friendship.getInitiator());
        existing.setMessage(friendship.getMessage());
        existing.setExpiresAt(friendship.getExpiresAt());
        existing.setUpdatedAt(friendship.getUpdatedAt());
    }

    @Override
    public void updateStatus(long actorX, long actorY, FriendshipStatus status) {
        Friendship row = rows.get(key(actorX, actorY));
        if (row != null) {
            row.setStatus(status);
            row.setUpdatedAt(LocalDateTime.now());
        }
    }

    @Override
    public boolean delete(long actorX, long actorY) {
        calls.add("delete");
        return rows.remove(key(actorX, actorY)) != null;
    }

    @Override
    public List<Long> listFriendIds(long actorId, int limit) {
        return rows.values().stream()
                .filter(row -> row.getStatus() == FriendshipStatus.ACCEPTED)
                .filter(row -> row.getActorA() == actorId || row.getActorB() == actorId)
                .map(row -> row.getActorA() == actorId ? row.getActorB() : row.getActorA())
                .distinct()
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public List<Friendship> pageFriends(long actorId, Cursor cursor, int limit) {
        return page(actorId, cursor, limit, row -> row.getStatus() == FriendshipStatus.ACCEPTED);
    }

    @Override
    public List<Friendship> pageRequests(long actorId, FriendshipStatus status, Boolean initiatedByMe,
                                         Cursor cursor, int limit) {
        return page(actorId, cursor, limit, row -> {
            if (status != null && row.getStatus() != status) {
                return false;
            }
            if (initiatedByMe == null) {
                return true;
            }
            boolean mine = row.getInitiator() != null && row.getInitiator() == actorId;
            return mine == initiatedByMe;
        });
    }

    @Override
    public int countInitiatedSince(long actorId, LocalDateTime since) {
        return (int) rows.values().stream()
                .filter(row -> row.getInitiator() != null && row.getInitiator() == actorId)
                .filter(row -> row.getCreatedAt() != null && !row.getCreatedAt().isBefore(since))
                .count();
    }

    // ------------------------------------------------------------------ 测试用

    /** 直接塞一行（用于构造「已经是好友」「已被拉黑」等前置状态）。 */
    public void seed(long requestId, long actorA, long actorB, long initiator,
                     FriendshipStatus status, LocalDateTime createdAt, LocalDateTime expiresAt) {
        Friendship row = new Friendship();
        row.setRequestId(requestId);
        row.setActorA(actorA);
        row.setActorB(actorB);
        row.setInitiator(initiator);
        row.setStatus(status);
        row.setCreatedAt(millis(createdAt));
        row.setExpiresAt(millis(expiresAt));
        row.setUpdatedAt(millis(createdAt));
        rows.put(key(actorA, actorB), row);
    }

    /** 模拟 DATETIME(3)：只保留毫秒。 */
    private static LocalDateTime millis(LocalDateTime time) {
        return time == null ? null : time.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    public Friendship get(long actorX, long actorY) {
        return rows.get(key(actorX, actorY));
    }

    public int size() {
        return rows.size();
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    private List<Friendship> page(long actorId, Cursor cursor, int limit,
                                 java.util.function.Predicate<Friendship> filter) {
        List<Friendship> out = rows.values().stream()
                .filter(row -> row.getActorA() == actorId || row.getActorB() == actorId)
                .filter(filter)
                .filter(row -> olderThan(row, cursor))
                .sorted((x, y) -> {
                    int byTime = y.getUpdatedAt().compareTo(x.getUpdatedAt());
                    return byTime != 0 ? byTime : Long.compare(y.getRequestId(), x.getRequestId());
                })
                .limit(Math.max(1, limit))
                .toList();
        return new ArrayList<>(out);
    }

    /** 与真实实现的游标谓词同口径：严格「比游标更旧」（时间相同则比 requestId 小）。 */
    private static boolean olderThan(Friendship row, Cursor cursor) {
        if (cursor == null) {
            return true;
        }
        int byTime = row.getUpdatedAt().compareTo(cursor.updatedAt());
        return byTime < 0 || (byTime == 0 && row.getRequestId() < cursor.requestId());
    }

    private static long[] ordered(long a, long b) {
        return a <= b ? new long[]{a, b} : new long[]{b, a};
    }

    private static String key(long a, long b) {
        long[] pair = ordered(a, b);
        return pair[0] + "_" + pair[1];
    }
}
