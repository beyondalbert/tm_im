package com.tm.im.domain.repository;

import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.ActorType;

import java.util.List;
import java.util.Optional;

/**
 * 参与者仓储 —— <b>人与 Agent 共用同一个接口</b>。
 *
 * <p>这里没有任何 {@code findHuman*} / {@code findAgent*} 方法，是刻意的：
 * 「对等」若只停留在文档里，第一次需要「只查人」的需求出现时就会破功。
 * 因此本接口不提供按 {@code actorType} 过滤的查询入口；
 * 需要 Agent 扩展信息（webhook、推送模式）时走 {@code AgentProfileRepository}，
 * 而不是把 Actor 拆成两套。
 *
 * <p>（唯一的例外是 {@link #pageForAdmin}：它确实收一个 {@code ActorType} 参数，
 * 但那是<b>后台的筛选条件</b>，不是两套模型——它返回的仍然是同一张表的同一批 Actor，
 * 而「运营想看一下现在有多少 Agent」是一个查询需求，不是一条业务规则。）
 */
public interface ActorRepository {

    Optional<Actor> findById(long actorId);

    Optional<Actor> findByHandle(String handle);

    boolean existsHandle(String handle);

    /** 插入并回填主键（主键由 Snowflake 生成，不由数据库自增）。 */
    Actor insert(Actor actor);

    /**
     * 更新可变的资料字段（{@code display_name} / {@code avatar_url} / {@code bio} / {@code status}）。
     *
     * <p>刻意不是「更新全部字段」：{@code id} / {@code actor_type} / {@code handle} /
     * {@code created_at} 都是不可变的（改 handle 等于换个人，而 ID 与类型改了就无从

     * 解释已有数据），把它们也放进 UPDATE 只是多几个永远不会用的写列。
     *
     * <p>实现约定：缺失的行（{@code actor} 不存在）静默不做事——它不改变调用方的
     * 可观察结果（更新一个不存在的行，结果就是什么都没有变），而抛异常会把
     * 「账号被并发删了」变成一次 500。
     */
    void update(Actor actor);

    /** 批量查询，用于消息推送时补齐发送者展示信息（避免 N+1）。 */
    List<Actor> findByIds(List<Long> actorIds);

    /**
     * 后台的参与者列表（M9）：按 {@code id} 倒序，可按类型 / 状态 / handle 前缀过滤。
     *
     * <p><b>这是本接口里唯一一个「能把人列表出来」的方法</b>，它之所以能存在，
     * 是因为调用方有权限：{@code /v1/admin} 与用户端是两套独立认证（DESIGN §2.4）。
     * 用户端任何地方都拿不到它——「列全站用户」这类接口由权限而不是由仓储的名字拦住。
     *
     * <p>分页游标只有一个 {@code beforeId}：{@code id} 是 Snowflake，单调递增且与
     * 创建时间同序，所以「按 id 倒序」就是「按注册时间倒序」，不必再带一个时间戳去
     * 处理同毫秒并列。过滤条件都为 null 时不过滤；{@code handlePrefix} 是前缀匹配
     * （运营场景里查人几乎总是「记得开头」而不是「记得全名」）。
     */
    List<Actor> pageForAdmin(Long beforeId, int limit, ActorType actorType, ActorStatus status,
                             String handlePrefix);
}
