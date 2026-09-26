package com.tm.im.api.user.view;

/**
 * §3.1 发好友请求的请求体。
 *
 * <p>支持两种写法（文档 §3.1）：{@code {"target": "@alice"}} 或
 * {@code {"target_actor_id": 1001}}。两种写法映射到同一个解析入口
 * （{@code ActorLookup.require}，它接受 {@code @handle} 或纯数字字符串），
 * 所以「怎么指向一个人」这条规则只有一处实现——见 03-rest-api.md §1.7。
 *
 * <p>两种都给时以 {@code target} 为准（而不是报「二选一」）：它们是同一件事的
 * 两种表达，而客户端在迁移期完全可能两个都发。若两者指向不同的人，
 * 那也应当按更具体/更早的约定取一个——{@code target} 是文档里排在前面的那个。
 */
public record FriendRequestRequest(String target, Long targetActorId, String message) {

    /**
     * @return 可供 {@code ActorLookup} 解析的写法
     * @throws com.tm.im.common.error.TmException 40001（两者都没给）
     */
    public String targetRef() {
        if (target != null && !target.isBlank()) {
            return target;
        }
        if (targetActorId != null) {
            return Long.toString(targetActorId);
        }
        throw new com.tm.im.common.error.TmException(
                com.tm.im.common.error.ErrorCode.MISSING_PARAMETER,
                "需要 target（@handle）或 target_actor_id（纯数字）");
    }
}
