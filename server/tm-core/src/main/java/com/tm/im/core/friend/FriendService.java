package com.tm.im.core.friend;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.id.IdGenerator;
import com.tm.im.core.agent.AgentProperties;
import com.tm.im.core.conversation.ConversationService;
import com.tm.im.core.conversation.PageCursors;
import com.tm.im.core.identity.ActorLookup;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.Friendship;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.FriendshipStatus;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.FriendshipRepository;
import com.tm.im.domain.support.PairKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 好友关系 —— 03-rest-api.md §3 的实现（DESIGN §11.4 的状态机）。
 *
 * <pre>
 *   发起 → friendship(PENDING) → 对方同意 → ACCEPTED（并自动开好单聊会话）
 *                              ↘ 拒绝 / 过期 → 这一行被删除
 * </pre>
 *
 * <h2>一次「加好友」只有一行记录</h2>
 *
 * <p>请求的生命周期与关系的状态<b>存在同一行上</b>（{@code friendship.status}），
 * 而不是「friend_request 表 + friendship 表」。后者看起来更规整，但它让
 * 「请求被接受了」与「关系存在」变成两次写入——中间任何一次失败或并发交错，
 * 都会留下「请求说已接受、关系表说不是好友」这种状态，而它的表现是
 * <b>用户明明同意了，对方却发不出消息</b>（40003），且重试无法自愈。
 *
 * <h2>这一层刻意不做的事</h2>
 *
 * <ul>
 *   <li><b>不做「互相请求即自动成为好友」</b>：那样一次误点（对方刚好也在点你）
 *       会在双方都不知情时建出一个会话。撞到待处理请求时回 40902，
 *       并告诉客户端「如果那条是对方发起的，同意它即可」（见 {@link #request}）。</li>
 *   <li><b>不做好友集缓存</b>：DESIGN §11.6 里的 {@code tm:friend:*} 缓存要连带解决
 *       跨节点失效，属于另一件事（见 DESIGN §14.1 的待办）。当前的取舍是
 *       「每次回源数据库」——它一定正确，代价是发单聊消息时多一次点查。</li>
 *   <li><b>删好友不删会话</b>：§3.5 明确说历史消息仍可见。删掉会话会让两个人的
 *       历史记录凭空消失，而他们只是「暂时不想再说话」。</li>
 * </ul>
 */
@Service
public class FriendService {

    private static final Logger log = LoggerFactory.getLogger(FriendService.class);

    /**
     * 一次列表请求最多扫多少行（两个方向各这么多）。
     *
     * <p>好友与请求的列表都按 {@code updated_at} 排好序再取一页，所以这个上限是
     * 「一页最多 limit+1 行」的放大版（limit 由 REST 层收敛到 200）。
     * 它不是为了性能，而是为了不让一次请求把整个关系表拉进内存——
     * 一个有两万个好友的账号照样能翻页，只是每页的扫描量有界。
     */
    private static final int SCAN_CAP = 1000;

    private final FriendshipRepository friendships;
    private final ActorRepository actors;
    private final ActorLookup actorLookup;
    private final ConversationService conversations;
    private final IdGenerator idGenerator;
    private final FriendProperties properties;
    private final AgentProperties agentProperties;
    private final ZoneId databaseZone;

    public FriendService(FriendshipRepository friendships,
                         ActorRepository actors,
                         ActorLookup actorLookup,
                         ConversationService conversations,
                         IdGenerator idGenerator,
                         FriendProperties properties,
                         AgentProperties agentProperties,
                         ZoneId databaseZone) {
        this.friendships = friendships;
        this.actors = actors;
        this.actorLookup = actorLookup;
        this.conversations = conversations;
        this.idGenerator = idGenerator;
        this.properties = properties;
        this.agentProperties = agentProperties;
        this.databaseZone = databaseZone;
    }

    // ================================================================== 发起请求

    /**
     * 发起好友请求（§3.1）。
     *
     * @param message 附言，可为空
     */
    public record RequestOutcome(Friendship friendship, Actor from, Actor to) {
    }

    /**
     * 发出一条好友请求。
     *
     * <p>这是<b>唯一不受「非好友不能发消息」限制的入口</b>（DESIGN §11.6 的冷启动）：
     * 它必须能在没有任何关系时被调用，否则「不能加好友 → 不能发消息 → 永远加不了好友」。
     *
     * <p>三件事按固定顺序判断（顺序本身是契约的一部分，见各自的注释）：
     * <ol>
     *   <li>目标是不是自己（40904）——先于查关系，因为「自己」根本没有关系行；</li>
     *   <li>日配额（42903）——先于查关关系，因为它与现有关系无关；</li>
     *   <li>现有关系（40901 / 40902 / 40903）。</li>
     * </ol>
     *
     * <p><b>过期与拒绝的区别</b>：拒绝会删掉整行（§11.4），过期不会——
     * 后者只让 {@code expires_at} 落在过去。因此「重新发起」要处理两种「旧行」：
     * 过期的 PENDING（覆盖它，换一个 request_id）与被拉黑的行（默认拒绝）。
     */
    public RequestOutcome request(long actorId, String targetRef, String message) {
        Actor target = actorLookup.require(targetRef);
        if (PairKeys.isSelf(actorId, target.getId())) {
            throw new TmException(ErrorCode.SELF_OPERATION, "不能加自己为好友 actorId=" + actorId);
        }

        int quota = dailyQuota(actorId);
        LocalDateTime since = LocalDate.now(databaseZone).atStartOfDay();
        int used = friendships.countInitiatedSince(actorId, since);
        if (used >= quota) {
            throw new TmException(ErrorCode.FRIEND_REQUEST_QUOTA_EXCEEDED,
                    "今日已发出 " + used + " 条（上限 " + quota + " 条/天，" + since.toLocalDate() + " 起计）");
        }

        LocalDateTime now = LocalDateTime.now(databaseZone);
        String cleanMessage = cleanMessage(message);

        Friendship existing = friendships.find(actorId, target.getId()).orElse(null);
        if (existing != null) {
            switch (existing.getStatus()) {
                case ACCEPTED -> throw new TmException(ErrorCode.ALREADY_FRIENDS,
                        "actorId=" + actorId + " 与 " + target.getId() + " 已是好友");
                case BLOCKED -> {
                    // 拉黑是「我不想再收到你的任何东西」。默认不允许再次请求，
                    // 否则它退化成「我暂时不想理你」——被拉黑的一方还能继续敲。
                    if (!properties.isAllowRequestAfterBlock()) {
                        throw new TmException(ErrorCode.BLOCKED,
                                "存在拉黑关系（blocker=" + existing.getInitiator() + "）");
                    }
                    log.info("拉黑关系上重新发起请求（tm.friend.allow-request-after-block=true）"
                            + "actorId={} target={}", actorId, target.getId());
                }
                case PENDING -> {
                    if (existing.getExpiresAt() != null && existing.getExpiresAt().isAfter(now)) {
                        // 未过期：两个方向都回同一个码。
                        // 若那条是**对方发给我的**，客户端的动作是「去请求列表把它同意了」，
                        // 而不是继续等——两种情况下要看的都是同一个列表，所以共用一个码。
                        throw new TmException(ErrorCode.REQUEST_PENDING,
                                "已有待处理请求 requestId=" + existing.getRequestId()
                                        + " initiator=" + existing.getInitiator()
                                        + " expiresAt=" + existing.getExpiresAt());
                    }
                    log.debug("旧的待处理请求已过期，覆盖它 requestId={} actorId={} target={}",
                            existing.getRequestId(), actorId, target.getId());
                }
            }
        }

        Friendship row = new Friendship();
        row.setRequestId(idGenerator.nextId());
        row.setActorA(actorId);
        row.setActorB(target.getId());
        row.setInitiator(actorId);
        row.setStatus(FriendshipStatus.PENDING);
        row.setMessage(cleanMessage);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setExpiresAt(now.plusDays(Math.max(1, properties.getRequestExpireDays())));
        friendships.save(row);

        log.info("好友请求已发出 requestId={} from={} to={} 今日第 {}/{} 条",
                row.getRequestId(), actorId, target.getId(), used + 1, quota);
        return new RequestOutcome(row, requireActor(actorId), target);
    }

    // ================================================================== 处理请求

    /** 同意（§3.2）。{@code convId} 是同意之后自动建好的单聊会话。 */
    public record AcceptOutcome(Friendship friendship, Actor from, Actor to, long convId) {
    }

    /**
     * 同意好友请求。
     *
     * <p><b>幂等</b>（§3.5 的重试表明确列了「同意好友请求」可安全重试）：
     * 已经是 ACCEPTED 时不再写库，但**仍然返回同一个 {@code conv_id}**
     * ——因为「重试用同一个结果」才是幂等的含义，回一个「已经同意了」的错误
     * 会让客户端把一次成功的操作当成失败。
     *
     * <p>返回自动建好的会话是为了省掉一次往返（§3.2 的「便利设计」）：
     * 客户端拿到 {@code conv_id} 就能直接开始聊天。会话由
     * {@link ConversationService#openDirect} 建，它本身就是幂等的
     * （{@code pair_key} 唯一索引），所以并发同意也不会建出两个会话。
     *
     * @throws TmException 40400（请求不存在/已过期）、40302（这条请求不是发给我的）
     */
    public AcceptOutcome accept(long actorId, long requestId) {
        Friendship f = requireRequest(requestId);
        requireAddressedToMe(actorId, f, "同意");
        if (f.getStatus() == FriendshipStatus.BLOCKED) {
            // 拉黑与同意是互斥的两件事，而这一行只能表示一种状态。
            // 回 40302 而不是 40400：请求确实存在，只是它已经被另一种意图取代了。
            throw new TmException(ErrorCode.PERMISSION_DENIED,
                    "requestId=" + requestId + " 的关系是拉黑状态，不能同意");
        }
        long other = otherSide(actorId, f);
        Actor peer = requireActor(other);

        if (f.getStatus() == FriendshipStatus.ACCEPTED) {
            log.debug("重复同意（幂等）requestId={} actorId={}", requestId, actorId);
            return new AcceptOutcome(f, requireActor(f.getInitiator()), requireActor(other),
                    conversations.openDirect(actorId, "@" + peer.getHandle()).conversation().getId());
        }
        requireNotExpired(requestId, f);

        f.setStatus(FriendshipStatus.ACCEPTED);
        // initiator 保持不变：它是「谁先开的口」，而 friends_since 用的是 updated_at
        // （仓储每次写入都会刷新它），所以两个字段各自表达一件不同的事。
        f.setMessage(null);   // 附言只在待处理时有意义，接受之后它不再是「请求的附言」
        friendships.save(f);

        long convId = conversations.openDirect(actorId, "@" + peer.getHandle())
                .conversation().getId();
        log.info("好友请求已同意 requestId={} {} <-> {} convId={}",
                requestId, f.getActorA(), f.getActorB(), convId);
        return new AcceptOutcome(f, requireActor(f.getInitiator()), requireActor(other), convId);
    }

    /** 拒绝（§3.2）。{@code friendship} 是被删掉的那一行（用于回显）。 */
    public record RejectOutcome(Friendship friendship, Actor from, Actor to) {
    }

    /**
     * 拒绝好友请求 —— <b>删掉整行</b>（DESIGN §11.4：拒绝即删除）。
     *
     * <p>为什么不留着当历史：留着就必须回答「这行是 PENDING 还是被人拒绝过」，
     * 而 {@code status} 里没有「已拒绝」这个取值——加一个的话，
     * 被拒绝的一方再发请求时又要判断「上一次被拒」要不要区别对待。
     * 删除之后的语义干净：没有这一行 = 没有关系，双方都能重新发起。
     */
    public RejectOutcome reject(long actorId, long requestId) {
        Friendship f = requireRequest(requestId);
        requireAddressedToMe(actorId, f, "拒绝");
        requireNotExpired(requestId, f);
        if (f.getStatus() != FriendshipStatus.PENDING) {
            throw new TmException(ErrorCode.PERMISSION_DENIED,
                    "requestId=" + requestId + " 的状态是 " + f.getStatus() + "，只有待处理的请求能拒绝");
        }
        friendships.delete(f.getActorA(), f.getActorB());
        log.info("好友请求已拒绝并删除 requestId={} by={}", requestId, actorId);
        return new RejectOutcome(f, requireActor(f.getInitiator()), requireActor(actorId));
    }

    // ================================================================== 列表

    /** 列表里的一项：关系行 + 涉及的两个人（from 是发起人，to 是接收人）。 */
    public record RelationEntry(Friendship friendship, Actor from, Actor to, boolean incoming) {
    }

    /** 分页结果。{@code nextCursor} 为 null 表示没有更多。 */
    public record Page<T>(List<T> items, String nextCursor, boolean hasMore) {
    }

    /** 好友列表的一行：关系行 + 对方。{@code friendsSince} 就是 {@code updated_at}。 */
    public record FriendEntry(Friendship friendship, Actor peer, LocalDateTime friendsSince) {
    }

    /**
     * 待处理/全部请求列表（§3.3）。
     *
     * @param incomingOnly true = 我收到的，false = 我发出的
     * @param pendingOnly  true = 只看待处理（并滤掉已过期的），false = 全部状态
     */
    public Page<RelationEntry> listRequests(long actorId, boolean incomingOnly, boolean pendingOnly,
                                            int limit, String cursor) {
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        PageCursors.SocialCursor from = cursor == null || cursor.isBlank()
                ? null : PageCursors.decodeFriendRequest(cursor);

        List<Friendship> rows = friendships.pageRequests(actorId,
                pendingOnly ? FriendshipStatus.PENDING : null,
                incomingOnly ? Boolean.FALSE : Boolean.TRUE,
                repositoryCursor(from),
                Math.min(pageSize + 1, SCAN_CAP));
        LocalDateTime now = LocalDateTime.now(databaseZone);
        List<Friendship> usable = new ArrayList<>(rows.size());
        for (Friendship row : rows) {
            // 过期的待处理请求按「不存在」处理（见 FriendProperties#getRequestExpireDays）：
            // 它既不该出现在列表里，也不该挡住同一对人重新发起。
            if (pendingOnly && isExpired(row, now)) {
                log.debug("跳过已过期的待处理请求 requestId={} expiresAt={}",
                        row.getRequestId(), row.getExpiresAt());
                continue;
            }
            usable.add(row);
        }

        boolean hasMore = usable.size() > pageSize;
        List<Friendship> page = hasMore ? usable.subList(0, pageSize) : usable;
        Map<Long, Actor> byId = loadActors(page.stream()
                .flatMap(row -> List.of(row.getActorA(), row.getActorB()).stream()).toList());
        List<RelationEntry> items = new ArrayList<>(page.size());
        for (Friendship row : page) {
            items.add(new RelationEntry(row, byId.get(row.getActorA()), byId.get(row.getActorB()),
                    !Objects.equals(row.getInitiator(), actorId)));
        }
        String next = hasMore && !page.isEmpty()
                ? PageCursors.encodeFriendRequest(epochMilli(page.get(page.size() - 1)), 
                        page.get(page.size() - 1).getRequestId())
                : null;
        return new Page<>(List.copyOf(items), next, hasMore);
    }

    /** 好友列表（§3.4），按「成为好友的时间」倒序。 */
    public Page<FriendEntry> listFriends(long actorId, int limit, String cursor) {
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        PageCursors.SocialCursor from = cursor == null || cursor.isBlank()
                ? null : PageCursors.decodeFriend(cursor);

        List<Friendship> rows = friendships.pageFriends(actorId, repositoryCursor(from),
                Math.min(pageSize + 1, SCAN_CAP));
        boolean hasMore = rows.size() > pageSize;
        List<Friendship> page = hasMore ? rows.subList(0, pageSize) : rows;

        // 对方是谁：我不在 actor_a 那边，那对方就是 actor_a。两张小集合各查一次，
        // 而不是每行一次（好友列表最容易写成 N+1 的地方）。
        List<Long> peerIds = new ArrayList<>(page.size());
        for (Friendship row : page) {
            peerIds.add(otherSide(actorId, row));
        }
        Map<Long, Actor> byId = loadActors(peerIds);
        List<FriendEntry> items = new ArrayList<>(page.size());
        for (int i = 0; i < page.size(); i++) {
            Friendship row = page.get(i);
            Actor peer = byId.get(peerIds.get(i));
            if (peer == null) {
                // 好友行指向一个不存在的 actor（账号被删或数据不一致）。
                // 记 ERROR 并跳过：静默返回一个 null 的 peer 会让客户端在渲染时崩。
                log.error("好友关系指向不存在的 actor requestId={} peerId={}",
                        row.getRequestId(), peerIds.get(i));
                continue;
            }
            items.add(new FriendEntry(row, peer, row.getUpdatedAt()));
        }
        String next = hasMore && !page.isEmpty()
                ? PageCursors.encodeFriend(epochMilli(page.get(page.size() - 1)),
                        page.get(page.size() - 1).getRequestId())
                : null;
        return new Page<>(List.copyOf(items), next, hasMore);
    }

    // ================================================================== 删除 / 拉黑

    /**
     * 删好友（§3.5）。
     *
     * <p><b>幂等</b>：本来就不是好友时也返回成功。这里与「踢群成员」刻意不同
     * （那里目标不在群里回 40908）——因为两者的客户端目标状态不同：
     * 踢人是「让这个人不在群里」，而删好友的客户端目标就是「我们不再是好友」，
     * 它已经成立。而且删除的入口只有自己（删别人的好友不需要知道对方是不是我好友），
     * 所以这里不存在「拼错了 id 却以为删成功」的风险——拼错 id 只会删掉一个不存在的对。
     *
     * @return 是否真的删掉了一行（仅供日志与测试；对外两者都是成功）
     */
    public boolean removeFriend(long actorId, long targetId) {
        requireNotSelf(actorId, targetId, "删好友");
        Actor target = requireActor(targetId);
        boolean removed = friendships.delete(actorId, targetId);
        log.info("删好友 actorId={} target={} removed={}", actorId, target.getId(), removed);
        // 副作用（§3.5）：单聊里双方此后发消息会得到 40003；历史消息不受影响。
        return removed;
    }

    /** 拉黑/解除拉黑的结果。 */
    public record BlockOutcome(long actorId, long targetId, boolean blocked) {
    }

    /**
     * 拉黑（§3.6）。
     *
     * <p>拉黑<b>复用同一行</b>并把 status 改成 BLOCKED，于是它同时表达了
     * 「我们不再是好友」（发消息 → 40304）与「不许再加我」。分两张表的话，
     * 「他是好友但被拉黑了」这种组合就必须在每个读取点各自处理一遍。
     *
     * <p>幂等：重复拉黑不报错（第二个人不会因此得到「已经没有这条关系」的错误）。
     */
    public BlockOutcome block(long actorId, long targetId) {
        requireNotSelf(actorId, targetId, "拉黑");
        Actor target = requireActor(targetId);
        LocalDateTime now = LocalDateTime.now(databaseZone);
        Friendship row = friendships.find(actorId, targetId).orElseGet(Friendship::new);
        if (row.getRequestId() == null) {
            row.setRequestId(idGenerator.nextId());
            row.setCreatedAt(now);
        }
        row.setActorA(actorId);
        row.setActorB(targetId);
        // initiator 记的是「谁拉黑谁」：解锁与「谁先开的口」是两件事，
        // 而这一行在成为拉黑关系之后，「谁先开口」已经没有意义了。
        row.setInitiator(actorId);
        row.setStatus(FriendshipStatus.BLOCKED);
        row.setMessage(null);
        friendships.save(row);
        log.info("拉黑 actorId={} target={}", actorId, targetId);
        return new BlockOutcome(actorId, targetId, true);
    }

    /**
     * 解除拉黑（§3.6）。
     *
     * <p><b>只删 BLOCKED 的行</b>：若当前是 ACCEPTED（比如在拉黑之后又通过别的方式
     * 恢复了关系），删除会把一段真实的好友关系悄悄抹掉，而调用者以为自己做的是
     * 「解除拉黑」。所以状态不是 BLOCKED 时它什么都不做并返回成功——后者是幂等。
     */
    public BlockOutcome unblock(long actorId, long targetId) {
        requireNotSelf(actorId, targetId, "解除拉黑");
        Actor target = requireActor(targetId);
        Friendship row = friendships.find(actorId, targetId).orElse(null);
        if (row != null && row.getStatus() == FriendshipStatus.BLOCKED) {
            friendships.delete(actorId, targetId);
            log.info("解除拉黑 actorId={} target={}", actorId, target.getId());
        }
        return new BlockOutcome(actorId, targetId, false);
    }

    // ================================================================== 内部

    private int dailyQuota(long actorId) {
        Actor actor = requireActor(actorId);
        // 人类与 Agent 的配额不同（07-errors-limits.md §3.1）：前者防手滑，后者防批量扫描。
        // 判据是 actor_type —— 这是「展示元数据之外的少数合法读取点」之一，
        // 因为它决定的是配额这个**策略参数**，而不是「谁有权限做什么」。
        return actor.getActorType() == ActorType.AGENT
                ? agentProperties.getFriendRequestDailyQuota()
                : properties.getRequestDailyQuota();
    }

    private Actor requireActor(long actorId) {
        return actors.findById(actorId).orElseThrow(() -> new TmException(
                ErrorCode.ACTOR_NOT_FOUND, "actorId=" + actorId));
    }

    private Friendship requireRequest(long requestId) {
        return friendships.findByRequestId(requestId).orElseThrow(() -> new TmException(
                ErrorCode.NOT_FOUND, "好友请求不存在或已被处理 requestId=" + requestId));
    }

    /** 只有<b>被请求方</b>能同意/拒绝。自己发起的请求自己批准没有意义（那不是同意，是自问自答）。 */
    private void requireAddressedToMe(long actorId, Friendship f, String action) {
        if (Objects.equals(f.getInitiator(), actorId)) {
            throw new TmException(ErrorCode.PERMISSION_DENIED,
                    "不能" + action + "自己发起的请求 requestId=" + f.getRequestId());
        }
        if (f.getActorA() != actorId && f.getActorB() != actorId) {
            throw new TmException(ErrorCode.PERMISSION_DENIED,
                    action + "请求需要是当事人 actorId=" + actorId
                            + " requestId=" + f.getRequestId());
        }
    }

    private void requireNotExpired(long requestId, Friendship f) {
        if (f.getStatus() == FriendshipStatus.PENDING && isExpired(f, LocalDateTime.now(databaseZone))) {
            // 过期的请求按「不存在」处理：保留它会让「接受一条三周前的请求」
            // 突然建出一个双方都不想要的会话。
            throw new TmException(ErrorCode.NOT_FOUND,
                    "好友请求已过期 requestId=" + requestId + " expiresAt=" + f.getExpiresAt());
        }
    }

    private static boolean isExpired(Friendship f, LocalDateTime now) {
        return f.getStatus() == FriendshipStatus.PENDING
                && f.getExpiresAt() != null && !f.getExpiresAt().isAfter(now);
    }

    private static void requireNotSelf(long actorId, long targetId, String action) {
        if (PairKeys.isSelf(actorId, targetId)) {
            throw new TmException(ErrorCode.SELF_OPERATION, "不能对自己" + action + " actorId=" + actorId);
        }
    }

    /** 这一行里「对方的 id」。 */
    private static long otherSide(long actorId, Friendship f) {
        if (Objects.equals(f.getActorA(), actorId)) {
            return f.getActorB();
        }
        if (Objects.equals(f.getActorB(), actorId)) {
            return f.getActorA();
        }
        throw new TmException(ErrorCode.PERMISSION_DENIED,
                "不是这段关系的当事人 actorId=" + actorId + " requestId=" + f.getRequestId());
    }

    /** 游标里的毫秒要换回库里的墙上时间（口径由 {@code tm.time.zone} 定义）。 */
    private long epochMilli(Friendship row) {
        return row.getUpdatedAt().atZone(databaseZone).toInstant().toEpochMilli();
    }

    /**
     * 附言：只做长度与空白处理，不做内容过滤。
     *
     * <p>长度上限取列宽 255 而不是放宽截断：截断会让发送方以为自己写全了，
     * 而对方收到的是半句话（与 {@code title} 同一取舍）。
     */
    private static String cleanMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String clean = message.strip();
        if (clean.length() > 255) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "附言长度 " + clean.length() + " 超过上限 255");
        }
        return clean;
    }

    private Map<Long, Actor> loadActors(Collection<Long> actorIds) {
        List<Long> distinct = actorIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, Actor> out = new HashMap<>();
        for (Actor actor : actors.findByIds(distinct)) {
            out.put(actor.getId(), actor);
        }
        return out;
    }

    /**
     * 仓储层的游标需要 {@code LocalDateTime}，而游标里存的是毫秒。
     *
     * <p>转换必须用 {@code databaseZone}：{@code DATETIME(3)} 的含义由它定义
     * （与 sharding.yaml 的 serverTimezone 一致）。用 UTC 换算不会报错，
     * 只会让「上一页的最后一项」在库里指向另一个时刻，于是分页重复或漏项——
     * 而且只在好友列表的第二页开始才看得出来。
     */
    private FriendshipRepository.Cursor repositoryCursor(PageCursors.SocialCursor from) {
        if (from == null) {
            return null;
        }
        return new FriendshipRepository.Cursor(
                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(from.updatedAtMillis()),
                        databaseZone),
                from.requestId());
    }
}
