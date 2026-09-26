package com.tm.im.api.user.view;

import java.util.List;

/**
 * 加人的响应（03-rest-api.md §4.9 的 {@code POST /v1/conversations/{conv_id}/members}）。
 *
 * <p><b>为什么是两张表而不是一个「成功/失败」</b>：这个接口收的是一批人，
 * 其中「已经在群里的」不该让另外几个也加不进去。所以它不是「部分失败」，
 * 而是「一部分有活要干、一部分没有」——{@code added} 是前者，
 * {@code already_members} 是后者，两者都是成功。
 *
 * <p>{@code added} 给的是完整的成员视图（而不是一串 id）：客户端手里只有它<b>发出去的</b>
 * handle，而后续的踢人/改角色接口要的是 {@code actor_id}——不回给它，它就得再拉一次
 * 会话详情才能把新成员画出来。
 *
 * <p>{@code already_members} 只给 id：这些人的资料客户端从会话详情里就有了，
 * 而这里的用户动作是「刷新一下列表」，不需要更多。
 */
public record MemberAddView(long convId,
                            List<MemberView> added,
                            List<Long> alreadyMembers,
                            long memberCount) {
}
