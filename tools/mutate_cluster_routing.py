#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给「集群路由与节点探活」做变异测试：把每条规则逐一改坏，看测试是否必然失败。

为什么需要它
    这一层（DESIGN §7.4：tm:route / tm:node / 心跳）的规则几乎都是
    **只在多实例、且出故障时才显形**的：

      * 顶号时旧连接顺手把新连接的路由删掉 —— 单实例看不出，
        表现是「他明明在线，却再也收不到跨节点推送」，而且无法自愈；
      * 节点键不带 TTL —— 崩溃的节点被永久当成存活，消息被投进没人订阅的频道，
        发送方还报告推送成功；
      * 续期失败后不重新注册 —— Redis 抖动一次，本节点就永远是「死的」；
      * 解绑不带 CAS —— 换节点重连时旧节点删掉新绑定，同样无法自愈；
      * 路由发布/释放时把 Redis 异常抛出去 —— 一次 Redis 抖动放大成「全站登不上」。

    这些都属于「改坏了照样能跑、而且看起来更正常」那一类，只能靠测试钉住；
    而测试本身也需要被验证——一个永远绿的测试与没有测试等价。

为什么在临时工作区里做
    直接改仓库里的文件，进程一旦被强杀（超时、Ctrl+C）就会留下一个被改坏的仓库。
    所以把 server/ 与 proto/ 拷到 `.tools/` 下的临时工作区（已 gitignore），
    在那里变异并跑 mvn；仓库本体只读，脚本结束时核对被变异过的文件哈希未变。

    工作区刻意放在**仓库内**的 `.tools/` 而不是系统临时目录：集成测试要读
    `deploy/conf/runtime/it.properties`（ItEnv 从当前目录逐层向上找），
    放在仓库内就能直接找到生成好的那份，而不必把带凭据的副本拷到系统临时目录 ——
    那份副本被强杀时会留在磁盘上。

怎么判断「被抓到了」
    不看 mvn 的控制台输出（受控制台编码影响，中文断言信息在 Windows 上是乱码），
    而是读 surefire 的纯文本报告（UTF-8）并检查**具体的失败用例名**：
    每个变异都必须让「它该触发的那个用例」失败。用例名变了说明钉住这条规则的测试没了。

用法
    uv run python tools/mutate_cluster_routing.py            # 全部（含需要真实 Redis 的 5 条）
    uv run python tools/mutate_cluster_routing.py --unit-only # 只跑不需要外部服务的 8 条
    uv run python tools/mutate_cluster_routing.py 顶号        # 只跑名字里含该串的变异
"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

REGISTRY = "server/tm-channel/src/main/java/com/tm/im/channel/cluster/ClusterAwareConnectionRegistry.java"
PROPERTIES = "server/tm-channel/src/main/java/com/tm/im/channel/cluster/NodeProperties.java"
HEARTBEAT = "server/tm-channel/src/main/java/com/tm/im/channel/cluster/NodeHeartbeat.java"
NODE_REGISTRY = "server/tm-channel/src/main/java/com/tm/im/channel/cluster/RedisNodeRegistry.java"
ROUTE_TABLE = "server/tm-channel/src/main/java/com/tm/im/channel/cluster/RedisActorRouteTable.java"
CLUSTER_KEYS = "server/tm-channel/src/main/java/com/tm/im/channel/cluster/ClusterKeys.java"

# 临时工作区只需要这两个目录：proto 是 tm-channel 的 protoSourceRoot（../../proto）。
COPY = ["server", "proto"]

# 工作区放在仓库内的 .tools/ 下：已 gitignore，且集成测试能向上找到 deploy/conf/runtime/
WORKSPACE = REPO / ".tools" / "mutate-cluster-ws"

MUTATION_TIMEOUT_SEC = 1800

# 每条变异的 (测试选择器, 是否需要真实 Redis)
UNIT_SELECTOR = ("ClusterAwareConnectionRegistryTest,NodeHeartbeatTest,NodePropertiesTest", False)
IT_SELECTOR = ("ClusterRedisIT", True)


def _needs_redis(selector: str) -> bool:
    return selector == IT_SELECTOR[0]


def _replace_once(text: str, old: str, new: str) -> str:
    if old not in text:
        raise AssertionError("变异定义已过期：找不到\n" + old)
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# 变异清单：(名字, 说明, 文件, 变异函数, 选择器, 必须失败的用例)
# ---------------------------------------------------------------------------

MUTATIONS = [
    (
        "顶号时无条件释放路由",
        "channelInactive 不再判断「本节点是否还有该 Actor 的连接」："
        "客户端在同节点重连（顶号）后，旧连接的清理动作会把新连接的路由删掉 ——"
        "该用户此后收不到任何跨节点推送，且无法自愈（老路由只在他下次重连时才重建）",
        REGISTRY,
        lambda t: _replace_once(t, """        if (local.isOnline(actorId)) {""",
                                """        if (false) {"""),
        UNIT_SELECTOR, "evictedConnectionMustNotReleaseTheNewRoute",
    ),
    (
        "路由发布失败让登录失败",
        "绑定路由时不再吞掉 Redis 异常：一次 Redis 抖动直接让鉴权抛异常，"
        "把「跨节点推送退化」放大成「全站登不上」（本地连接其实完全可用）",
        REGISTRY,
        lambda t: _replace_once(t, """        try {
            routes.bind(actorId);
        } catch (RuntimeException e) {
            log.warn("本地已上线但集群路由发布失败 actorId={}：跨节点推送将退化为「等对方重连后 SYNC 补齐」，"
                    + "本机推送不受影响。cause={}", actorId, e.toString());
        }""",
                                """        routes.bind(actorId);"""),
        UNIT_SELECTOR, "routePublishFailureDoesNotBreakLogin",
    ),
    (
        "路由释放失败向上抛",
        "释放路由时不再吞掉 Redis 异常：断开连接是最频繁的路径，"
        "抛出会污染调用方的收尾逻辑（而留下的陈旧路由本来会被「本节点已无该连接」挡住）",
        REGISTRY,
        lambda t: _replace_once(t, """        try {
            routes.unbind(actorId);
        } catch (RuntimeException e) {""",
                                """        try {
            routes.unbind(actorId);
        } catch (IllegalArgumentException e) {"""),
        UNIT_SELECTOR, "routeReleaseFailureDoesNotBreakLogout",
    ),
    (
        "心跳间隔 >= TTL 不再被拒绝",
        "去掉「heartbeat < ttl」的启动校验：心跳键在两次续期之间必然过期，"
        "本节点会被别的节点反复判死 —— 表现是跨节点推送间歇性失效，"
        "而资源、连接数、日志都没有任何异常",
        PROPERTIES,
        lambda t: _replace_once(t, """        if (heartbeatMillis >= ttl.toMillis()) {""",
                                """        if (false) {"""),
        UNIT_SELECTOR, "heartbeatMustBeSmallerThanTtl",
    ),
    (
        "显式配置的 nodeId 被忽略",
        "resolve 永远按「主机名:端口」推导：多实例部署时显式配置的 TM_NODE_ID 失效，"
        "容器编排给多次部署同样的主机名与端口时，两个实例会共用一个 nodeId，"
        "路由指向错误的进程（发送方还报告推送成功）",
        PROPERTIES,
        lambda t: _replace_once(t, """        String nodeId = auto ? hostname + ":" + nettyPort : explicit;""",
                                """        String nodeId = hostname + ":" + nettyPort;"""),
        UNIT_SELECTOR, "explicitIdWinsAndBlanksFallBackToAuto",
    ),
    (
        "续期失落后不重新注册",
        "续期返回 false（键被清理 / Redis 重启过）时什么都不做："
        "本节点在别的节点眼里永远是死的，而本节点一切正常、日志只有一行 INFO",
        HEARTBEAT,
        lambda t: _replace_once(t, """            if (!nodes.renew(self.nodeId(), properties.getTtl())) {""",
                                """            if (false) {"""),
        UNIT_SELECTOR, "renewFailureTriggersReRegistration",
    ),
    (
        "停机不注销节点",
        "stop 不再删 tm:node 键：优雅停机后其它节点仍以为本节点存活，"
        "继续把消息投向它的频道（无人订阅），直到 TTL 到期",
        HEARTBEAT,
        lambda t: _replace_once(t, """            nodes.unregister(self.nodeId());\n""", ""),
        UNIT_SELECTOR, "stopUnregistersAndStopsTicking",
    ),
    (
        "心跳相位晚于 Netty",
        "把心跳的启动相位调到 NettyServer(1000) 之后：端口开始接受连接时，"
        "「本节点还活着」尚未写进 Redis —— 第一个连接的路由在别人眼里指向一个死节点，"
        "停机顺序也随之反了（先注销、后停止投递）",
        HEARTBEAT,
        lambda t: _replace_once(t, "    static final int PHASE = 500;",
                                "    static final int PHASE = 1500;"),
        UNIT_SELECTOR, "phaseRunsBeforeTheNettyServer",
    ),
    (
        "节点键不带 TTL",
        "注册节点时不设过期时间：崩溃的节点被永久当成存活，"
        "别的节点会一直把消息投进没人订阅的频道，而发送方报告推送成功",
        NODE_REGISTRY,
        lambda t: _replace_once(
            t, "redis.opsForValue().set(ClusterKeys.node(info.nodeId()), Json.write(info), ttl);",
            "redis.opsForValue().set(ClusterKeys.node(info.nodeId()), Json.write(info));"),
        IT_SELECTOR, "nodeKeyExpiresOnItsTtl",
    ),
    (
        "续期恒返回 true",
        "renew 不看 EXPIRE 的结果：键早就没了却报告「续期成功」，"
        "于是 NodeHeartbeat 永远不会重新注册，本节点永远判死",
        NODE_REGISTRY,
        lambda t: _replace_once(t, "        return Boolean.TRUE.equals(redis.expire(ClusterKeys.node(nodeId), ttl));",
                                "        return true;"),
        IT_SELECTOR, "renewExtendsAndReportsMissingKeys",
    ),
    (
        "locate 不校验目标节点存活",
        "查路由时直接采信 tm:route 的值：持有者已经崩了也照投，"
        "消息进了一个无人订阅的频道，而发送方以为推送成功了（只有对方重连时才靠 SYNC 补）",
        ROUTE_TABLE,
        lambda t: _replace_once(t, """        if (nodes.isAlive(nodeId)) {""", """        if (true) {"""),
        IT_SELECTOR, "locateTrustsOnlyAliveNodesAndHealsAfterRestart",
    ),
    (
        "解绑不带 CAS",
        "unbind 改成无条件删除：客户端换节点重连时，旧节点的清理动作会删掉新节点刚写入的绑定 ——"
        "该用户在其连接存活期间一直收不到跨节点推送，且无法自愈",
        ROUTE_TABLE,
        lambda t: _replace_once(
            t,
            """        Long deleted = redis.execute(UNBIND_IF_OWNER, List.of(ClusterKeys.route(actorId)), self.nodeId());
        return deleted != null && deleted > 0;""",
            """        return Boolean.TRUE.equals(redis.delete(ClusterKeys.route(actorId)));"""),
        IT_SELECTOR, "unbindOnlyDeletesOwnRoute",
    ),
    (
        "路由键名被改",
        "tm:route:{actorId} 改名成 tm:routes:{actorId}（一个纯字符串的改动，编译毫无察觉）："
        "滚动升级期间新旧版本会各写各的键，路由彼此查不到 —— 功能还在工作，只是慢"
        "（每个消息都要等对方重连时用 SYNC 补）",
        CLUSTER_KEYS,
        lambda t: _replace_once(t, 'private static final String ROUTE_PREFIX = "tm:route:";',
                                'private static final String ROUTE_PREFIX = "tm:routes:";'),
        IT_SELECTOR, "locateReturnsSelfWithoutConsultingLiveness",
    ),
]

# 仓库里这些文件必须自始至终不变（脚本只读它们）
READONLY_IN_REPO = sorted({m[2] for m in MUTATIONS})


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def make_workspace() -> Path:
    if WORKSPACE.exists():
        shutil.rmtree(WORKSPACE, ignore_errors=True)
    WORKSPACE.parent.mkdir(parents=True, exist_ok=True)
    ignore = shutil.ignore_patterns("target", "__pycache__", "mutate-cluster-ws")
    for rel in COPY:
        src = REPO / rel
        if src.is_dir():
            shutil.copytree(src, WORKSPACE / rel, ignore=ignore)
        else:
            shutil.copy2(src, WORKSPACE / rel)
    return WORKSPACE


def run_tests(workspace: Path, selector: str, needs_redis: bool) -> tuple[int, str, str]:
    """在临时工作区里跑指定的测试。

    返回 (退出码, 所有 surefire 报告拼起来的文本, mvn 输出的末尾片段)。
    第三项用于「跑都没跑起来」的情况（编译失败、端口被占）：那时报告目录是空的，
    只报「看不到该用例」会让人以为是测试改名了，而真正的原因在 mvn 输出里。
    """
    argv = ["mvn", "-B", "-o", "test", "-pl", "tm-channel", "-am",
            "-Dtest=" + selector, "-Dsurefire.failIfNoSpecifiedTests=false"]
    if needs_redis:
        argv.append("-Pit")
    if os.name == "nt":
        argv = ["cmd", "/c"] + argv

    reports_dir = workspace / "server" / "tm-channel" / "target" / "surefire-reports"
    shutil.rmtree(reports_dir, ignore_errors=True)
    proc = subprocess.run(argv, cwd=workspace / "server", capture_output=True,
                          timeout=MUTATION_TIMEOUT_SEC,
                          env={**os.environ, "PYTHONIOENCODING": "utf-8"})

    text = []
    for report in sorted(reports_dir.glob("*.txt")):
        text.append(report.read_text(encoding="utf-8", errors="replace"))
    raw = (proc.stdout or b"").decode("utf-8", "replace") + (proc.stderr or b"").decode("utf-8", "replace")
    return proc.returncode, "\n".join(text), raw[-2000:]


def main() -> int:
    # Windows 默认控制台编码是 GBK，而这里的输出全是中文与符号：
    # 不显式改成 UTF-8 的话，重定向到文件时会直接 UnicodeEncodeError。
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    argv = [a for a in sys.argv[1:] if not a.startswith("-")]
    unit_only = "--unit-only" in sys.argv[1:]
    selected = [m for m in MUTATIONS if not argv or any(a in m[0] for a in argv)]
    if unit_only:
        selected = [m for m in selected if not m[4][1]]
    if not selected:
        print("没有匹配的变异名：" + ", ".join(m[0] for m in MUTATIONS))
        return 2

    before = {rel: sha(REPO / rel) for rel in READONLY_IN_REPO}
    workspace = make_workspace()
    print("临时工作区: {}".format(workspace))
    failures: list[str] = []
    caught = 0
    try:
        # 每个变异都从原始文件重新开始：否则多个变异落在同一个文件时，
        # 后一个的目标文本可能已被前一个删掉，那时「变异未生效」会被误判成「测试没抓到」。
        pristine = {rel: (workspace / rel).read_text(encoding="utf-8")
                    for rel in {m[2] for m in selected}}

        print("\n步骤 0：先确认未变异时这些测试是绿的（否则后面全无意义）")
        for selector, needs_redis in sorted({m[4] for m in selected}):
            label = "集成测试（需真实 Redis）" if needs_redis else "单元测试"
            code, report, raw = run_tests(workspace, selector, needs_redis)
            reports_ok = report.count("Failures: 0, Errors: 0") >= len(selector.split(","))
            if code != 0 or not reports_ok:
                print("  ❌ 未变异就无法通过：{} {}".format(label, selector))
                print("     " + report[:2000].replace("\n", "\n     "))
                print("     mvn 输出末尾：" + raw.replace("\n", "\n     "))
                return 2
            print("  ✅ {} {} 通过".format(label, selector))

        print("\n逐条改坏，看它该触发的用例是否必然失败")
        for name, why, rel, mutate, test_spec, must_fail in selected:
            selector, needs_redis = test_spec
            target = workspace / rel
            try:
                mutated = mutate(pristine[rel])
            except AssertionError as e:
                failures.append("{}：{}".format(name, e))
                print("  ⚠️  {}：{}".format(name, e))
                continue
            if mutated == pristine[rel]:
                failures.append("{}：变异没有改变文件内容".format(name))
                print("  ⚠️  {}：变异没有改变文件内容".format(name))
                continue

            target.write_text(mutated, encoding="utf-8", newline="")
            code, report, raw = run_tests(workspace, selector, needs_redis)
            target.write_text(pristine[rel], encoding="utf-8", newline="")

            if code == 0:
                failures.append("{}：测试竟然全绿 —— 这条规则没有人钉住".format(name))
                print("  ❌ {}：测试未察觉（该用例是 {}）".format(name, must_fail))
                continue
            if must_fail not in report:
                failures.append("{}：测试失败了，但不是 {}（是不是用例改名了？）".format(name, must_fail))
                print("  ⚠️  {}：失败了，但看不到用例 {}".format(name, must_fail))
                if report.strip():
                    print("     报告开头：" + report[:1200].replace("\n", "\n     "))
                else:
                    print("     没有测试报告（编译失败？）mvn 输出末尾："
                          + raw.replace("\n", "\n     "))
                continue
            caught += 1
            print("  ✅ {}：{} 失败（正是它该抓的）".format(name, must_fail))
            print("     {}".format(why))
    finally:
        shutil.rmtree(workspace, ignore_errors=True)

    print("\n仓库本体未被改动核对")
    for rel in READONLY_IN_REPO:
        same = sha(REPO / rel) == before[rel]
        print("  {} {}".format("✅" if same else "❌", rel))
        if not same:
            failures.append("仓库本体被改动了：" + rel)

    print("\n" + "=" * 74)
    if failures:
        print("FAILED ({} 项)：".format(len(failures)))
        for f in failures:
            print("  - " + f)
        return 1
    print("{} 个变异全部被捕获：集群路由与节点探活的规则都有测试钉住 ✓".format(caught))
    return 0


if __name__ == "__main__":
    sys.exit(main())
