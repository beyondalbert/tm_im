# tm_im

**第一个人和 Agent 对等的 IM。**

> 不是"给 Agent 用的聊天工具"，也不是"带机器人插件的微信"。
> 核心命题：**人（Human）与 Agent 是同一类参与者（Actor）**，拥有同一套消息、会话、好友、动态能力，唯一差别是"接入协议"。

## 文档

- **[总体设计 docs/DESIGN.md](docs/DESIGN.md)** ← 架构与选型
- **[接入文档 docs/integration/](docs/integration/README.md)** ← 不用 SDK 也能接入

### 接入文档导航

| 文档 | 内容 |
|---|---|
| [接入总览](docs/integration/README.md) | 三条接入路径、五分钟跑通 |
| [核心概念](docs/integration/01-concepts.md) | Actor 对等、seq、幂等、投递语义 |
| [认证](docs/integration/02-auth.md) | JWT / api_key / Webhook 签名 |
| [REST API](docs/integration/03-rest-api.md) | 完整接口参考 |
| [长连接协议](docs/integration/04-realtime.md) | **字节级**协议规范 |
| [Webhook](docs/integration/05-webhook.md) | 事件、验签、重试与幂等 |
| [零依赖裸实现](docs/integration/06-no-sdk-guide.md) | **手写 protobuf**，不用任何库 |
| [错误码与限流](docs/integration/07-errors-limits.md) | 排错速查 |

## 功能范围

| 功能 | 说明 |
|---|---|
| 1V1 消息 | 文字 + 图片 |
| 群聊 | 写扩散 / 读扩散按群规模自动切换 |
| 广场 | 朋友圈信息流，好友动态优先 |
| 加好友 | 双向对称关系 |
| Agent 接入 | 注册即获得与人同权的 Actor 身份 |

## 技术栈

- **后端**：Java 17 + Spring Boot 3.5.16 + MyBatis-Plus + **ShardingSphere-JDBC 5.5.3**
- **长连接**：**自研 Netty 4.1.x**（Protobuf 二进制帧）
- **存储**：MySQL 5.7 / 8.0（现网 5.7.44，按 `conv_id` 分片）+ Redis
- **前端**：Vue 3 + Vite + TypeScript
- **交付**：用户端、管理后台各为**一个可执行 JAR**

## 目录

```
docs/     设计文档 + 接入文档
deploy/   配置模板（sharding.yaml / application-external.yml）与建表脚本
deploy/sql/  建表脚本（由 tools/gen_schema.py 生成，勿手改）
proto/    长连接协议定义（transport.proto，可直接 protoc 编译）
tools/    校验、生成与引导脚本
server/   后端（Maven 聚合工程：10 个子模块，两个可执行 JAR）
```

尚未开始的两个目录（写在 DESIGN §12 的规划里，而代码里还没有）：
`web/h5` 用户端 Vue 3、`web/admin` 后台 Vue 3。两者的接口都已就绪，
但它们不阻塞任何后端验收（DESIGN §14.1 与 §14.2 把它们列在明处，而不是默认“应该有”）。

## 自检

一条命令跑完所有验证：

```bash
uv run --with protobuf --with pyyaml --with sqlglot python tools/verify_all.py
```

| 检查项 | 内容 |
|---|---|
| `schema` | 建表 SQL 语法 + **分片语义约束**（主键/幂等键必须含分片列） |
| `server-sql` | 服务端核验脚本的语法与 information_schema 列引用 |
| `docs` | 接入文档字节样本与签名向量 + **命令字契约三方一致**（proto ↔ §2.1 ↔ 服务端 `Frames`） |
| `shard` | 分片口径三方一致（DDL ↔ 模板 ↔ 代码）+ 配置可解析 + **`!SINGLE` 单表规则必须非空** |
| `errors` | 错误码契约：文档 §2 ↔ 枚举 |
| `entities` | 实体类与建表 SQL 一致 |
| `design-ddl` | **DESIGN §9 的 DDL 与生成器逐列一致**（带 8 类漂移的自检：缺列/类型/可空性/默认值/索引/多表/漏表） |
| `samples` | Webhook 签名测试向量 |
| `config-tmpl` | 配置模板与 `@ConfigurationProperties` 一致（缺项 / 拼错 / 默认值不一致） |
| `mutate` | **变异测试**：验证建表校验器真的能抓到错误 |
| `mutate-deps` | **变异测试**：验证 ShardingSphere 依赖缺失与配置陷阱必被守卫捕获（8 类） |
| `mutate-cfg` | **变异测试**：验证模板校验器能抓到 7 类缺陷 |
| `mutate-cmd` | **变异测试**：验证命令字契约校验器能抓到 10 类缺陷（方向写反、编号错、漏登记、漏写方向…） |
| `mutate-member` | **变异测试**：群成员管理的 14 条规则（权限矩阵与状态机）都有测试钉住 |
| `mutate-admin` | **变异测试**：后台的 6 条规则（越权、分页夹取、码值校验、停用删会话、失败计数）都有测试钉住 |
| `mutate-sync` | **变异测试**：续传读取路径的 10 条规则都有测试钉住 |

`--quick` 跳过耗时的 `docs` / `mutate` / `mutate-deps` / `mutate-cfg` / `mutate-cmd` / `mutate-member` / `mutate-admin`。

另有 5 项需要外部 MySQL/Redis（`runtime-cfg` = 运行时配置是否与当前
`local-conn.env` 一致，`schema-drift` = 库结构与 DDL 是否一致，`collation` = 实测服务端排序规则等价性，
`mutate-cluster` = 集群路由/节点探活/跳节点推送的 17 条规则，
`mutate-member-tx` = 转让群主的事务性），
用 `--services` 打开：

```bash
uv run --with protobuf --with pyyaml --with sqlglot --with pymysql \
  python tools/verify_all.py --services
```

不加 `--services` 时脚本会明确打印「跳过了 N 项需要外部服务的检查」——
而不是让读者以为绿色代表全都验过了。

### 建表 SQL 为什么是生成的

`deploy/sql/01-schema.sql` **不要手工编辑**。16 张分片表结构必须完全一致，
手写 16 遍必然漂移，而漂移在 ShardingSphere 下不会报错，只会在某个分片上静默丢约束。

```bash
python tools/gen_schema.py              # 重新生成
python tools/gen_schema.py --check      # 校验已生成文件是否为当前定义
python tools/gen_entities.py            # 由 DDL 反向生成实体与枚举
uv run --with sqlglot python tools/verify_schema.py   # 深度校验
```

> 三条命令的顺序是固定的：**改生成器 → 重跑两个生成器 → 迁库**（下面那条）。
> 实体是**反向生成**的（从 DDL 读出来），所以它不会漂：漏跑 `gen_entities.py`
> 会让实体缺字段，而那种缺陷在编译期就消失（字段只是不存在，MyBatis 照样跑），
> 表现为「某个字段永远是 null」——由 `gen_entities.py --check` 拦在 CI 里。

### 结构变更怎么落到已存在的库上

建表脚本里的每条语句都是 `CREATE TABLE IF NOT EXISTS`。这对「从零建库」是正确的，
对「加一列」则**什么都不做**——而报错只会在真正用到那一列时出现：
`Unknown column 'friendship.request_id' in 'field list'`，看起来像业务代码写错了字段名。

```bash
python tools/migrate_schema.py --dry-run                    # 看要做什么（不连库）
uv run --with pymysql python tools/migrate_schema.py --apply # 真的迁
uv run --with pymysql python tools/migrate_schema.py --check # 只查偏差（verify_all --services 会跑）
```

它只生成 `ADD COLUMN` / `ADD KEY` / `CREATE TABLE`：**没有 DROP、没有 MODIFY**。
删列与改类型是破坏性的（数据会没），必须由人显式决定，所以「库里有、DDL 里没有」
的列与索引只报 WARN 并列出建议语句。默认 dry-run，`--apply` 才连库。

校验器经过**变异测试**（`tools/mutate_schema.py`）：向正确的建表脚本里注入 14 类真实错误，
断言每一类都被拦下。为什么需要它——一个只会输出 OK 的校验器，和一个什么都抓不到的校验器，
在正常代码上表现完全一样，区别只在注入错误时才显现：

```bash
uv run --with sqlglot python tools/mutate_schema.py
```

```
idem_key_drops_shard_col     已捕获   幂等唯一键未含分片列
pk_drops_shard_col           已捕获   主键未含分片列 conv_id
one_shard_table_drift        已捕获   检测到 2 种不同结构 —— 有表结构漂移
second_precision_time        已捕获   created_at 用了秒级精度
myisam_engine                已捕获   非 InnoDB
utf8_instead_of_utf8mb4      已捕获   非 utf8mb4
missing_business_table       已捕获   缺少业务表
shard_count_not_pow2         已捕获   分片数 15 不是 2 的幂
directed_friendship_pk       已捕获   friendship 主键设计错误
collation_8_0_only           已捕获   使用了版本专有排序规则
collation_missing            已捕获   25 张表未显式声明 COLLATE
collation_in_comment         已捕获   文件（含注释）出现 8.0 专有排序规则
yaml_wrong_key_name          已捕获   无法从 sharding.yaml 解析 actualDataNodes
yaml_shard_count_mismatch    已捕获   分片数不一致：yaml=8 DDL=16
```

若变异**未生效**（目标文本没找到），脚本会直接报错而不是静默跳过——
否则一个写错了的变异会伪装成"校验器很厉害"。

### 配置模板为什么也要机器校验

`deploy/conf/application-external.yml.example` 是**运维视角的权威文档**：
部署方照它写配置。但它与代码之间没有任何编译期约束，所以必然漂移，
而漂移的方向总是最坏的一种——**模板缺项**。缺项不会报错：
运维没配那一项，服务就用代码默认值，而「模板里没写」看起来完全等于
「这一项不需要配」。M2 收尾时实测到 5 个缺项（包括整个 `tm.identity` 段）
与 1 个危险默认值（`${TM_WORKER_ID:1}` 让每个实例拿到同一个 Snowflake 节点号，
多实例部署即主键撞车，而单实例测试永远发现不了）。

```bash
uv run --with pyyaml python tools/verify_config_template.py    # 校验
uv run --with pyyaml python tools/mutate_config_template.py    # 证明校验器不是摆设
```

它校三个方向：代码 → 模板（缺项）、模板 → 代码（拼写错误会被 Spring
**静默忽略**）、以及默认值一致性（代码里没有默认值的项，模板必须用
`${ENV}` 且不给默认值——否则仓库里就躺着一把能用的密钥）。

### 示例密钥为什么是「16 位且不连续」的

`api_key` 的真实形状是 `sk_live_` + 32 位十六进制。但**文档与测试里不能写足 32 位**：
GitHub 的密钥扫描是按形状匹配的，一个 32 位十六进制的示例会被判成真的 Stripe Key，
于是**整条 `git push` 被仓库规则拒绝**（`remote rejected`，并列出命中的提交与行号）。
两个示例值从 M2/M8 起就躺在历史里，只改工作区没用，所以收尾时做了一次历史改写。

仓库里因此有两条约定：

- 文档一律用 16 位示例值 `sk_live_9f2c1d7a4b8e3f60`，并在紧邻处说明**真实长度**
  （`docs/integration/03-rest-api.md` §7.1 就是这么处理 `api_key` 的）；
- 测试里那个「故意带一段不该泄漏的尾巴」的常量改成**拼接书写**
  （`"sk_live_" + "0123456789abcdef" + "SECRETPART"`）——运行时拼出的字符串
  逐字节不变，但源码里没有任何一段长得像密钥。

### 服务端测试：单元 / 集成两层

```bash
# 单元测试：离线可跑，不需要数据库与凭据
mvn -o test

# 集成测试：需要真实 MySQL 与 Redis，用 -Pit 显式激活
uv run python tools/gen_runtime_config.py
mvn -o test -Pit
```

集成测试**刻意做成"缺配置就失败"**，而不是"缺配置就跳过"：
跳过会让"没验证"与"验证通过"呈现同一个绿色。
配置的默认位置是 `deploy/conf/runtime/`（由上一个命令生成），
测试会从当前目录逐层向上找它；要换一份时用 `-Dtm.it.config=` / `-Dtm.it.properties=` 覆盖。

> 这两个参数曾经是**必填**的（不传就直接失败，而报错说的是"缺凭据"，
> 与"参数没给"是两件事）。改成「约定优先 + 参数覆盖」之后，
> `mvn -o test -Pit` 本身就能跑，而两个测试类不再各持一套约定。

启动集成测试是其中最重的一个（真启 Tomcat + Netty + ShardingSphere + Redis，约 16 秒）：

```bash
mvn -o -pl tm-app -am test -Pit -Dtest=AppHttpIT -Dsurefire.failIfNoSpecifiedTests=false
```

`gen_runtime_config.py` 除 sharding.yaml / application.yml 外还生成一份
`deploy/conf/runtime/it.properties`：扁平键值对的 MySQL + Redis 坐标。
为什么不直接让 Java 去读 `application.yml`——那份文件里的 Redis 地址本来就写成
`${TM_REDIS_HOST}` / `${TM_REDIS_PASSWORD}`（有意留给部署环境变量），
在测试里解析它就等于把 Spring 的配置层重写一遍，写错了以"连不上"的形式暴露，看起来像服务故障。

跑单个 IT 并看过滤后的输出（控制台是 GBK，直接管道 mvn 还会触发死管）：

```bash
uv run python tools/run_it.py ConversationSeqAllocationIT
```

### 长连接（M2）：真连接端到端测试

M2 的验收标准是「两个客户端 WS 连通并可互发 PING/PONG」。它由
`server/tm-channel/src/test/java/.../server/TwoClientEndToEndTest.java` 覆盖，
跑在默认的 `mvn -o test` 里（不需要数据库）：

```bash
mvn -o -pl tm-channel -am test
```

它起一个真实的 Netty 服务（端口 0 = 系统分配），用真实的 socket 客户端走完整链路：
协议分流 → 编解码 → 限流 → 握手鉴权（过业务线程池）→ 连接注册表 → 心跳。

两件事值得记住：

1. **客户端是独立实现**（`wire/RawProto` + `wire/RawClient`）：手写 protobuf 字节、
   手写 RFC 6455 帧与握手（连 `Sec-WebSocket-Accept` 都自己算），不复用服务端任何一行代码。
   两端共用 protobuf-java 的话，「服务端理解错了字段」与「客户端按同样的错误构造」
   会互相抵消，测试依然是绿的。同时它把校验器写得**不宽容**：遇到不认识的线类型、
   字节流截断、字段类型与预期不符，一律报错而不是跳过。
2. **它在第一次运行时就抓到了 3 个致命缺陷**，而 24 个 `EmbeddedChannel` 单测全绿：
   `ConnectionLimiter` 未标 `@Sharable`（第二条连接起全部建不起来）、
   `AuthHandler` 的握手超时任务从未启动（`channelActive` 早就发过了）、
   以及「同一个 initializer 被多条连接共用」这条装配期不变式没有任何测试。
   它们全部是**结构上单测覆盖不到**的，因此现在有了
   `ChannelPipelineInitializerTest` 来钉住那条不变式。

定时器类行为（握手超时、心跳）的确定性写法也在用例注释里写清楚了：
`freezeTime()` 要在调度**之前**调，`advanceTimeBy()` 之后必须再调 `runScheduledPendingTasks()`
（它只推时钟、不跑任务），否则测试会「成功地」证明不了任何事。

测试残留数据可按标记清理（断言失败或进程被强杀时会用到）：

```bash
uv run --with pymysql python tools/clean_it_leftovers.py          # 只统计
uv run --with pymysql python tools/clean_it_leftovers.py --apply  # 真删
```

后台的残留也在它的范围里（M9）：`admin_user` 的用户名有唯一索引，
残留一条就会让下一次跑集成测试的建号直接撞 `uk_username` —— 而那个报错看起来像权限问题。
它按同一套 `it_` 前缀删账号，并连带删掉那个账号的会话行与审计行。

### 消息序号（M3）：为什么先钉住它，而不是先写业务

消息表的物理主键是 `(conv_id, seq)`，序号重复的后果不是"顺序乱"而是**那条消息插不进去**：
客户端看到的是"发出去没反应"，而网络、鉴权、连接全部正常。
所以 M3 写 `MessageService` 之前，先把序号这块地基用真实 MySQL + 真实 Redis 钉住：
`server/tm-storage/src/test/java/.../repository/ConversationSeqAllocationIT.java`（5 个用例）。

| 用例 | 钉住的事实 |
|---|---|
| 并发 8 线程 × 25 次取号 | Redis `INCR` 路径下每号唯一，且恰好用满 `1..200` |
| 清空 `tm:seq:{convId}` | 清空后**确实**从 1 重新开始——这是缺陷的复现，也是`raiseSeqFloor` 存在的理由 |
| `raiseSeqFloor(convId, 1)`（低于当前值） | 自愈只能抬、不能降；过期的基线不能把已发出的号拉回去 |
| `nextSeqFromDb(convId, 42)` | 兜底路径从基线之上继续，而不是从 `seq_counter` 旧值发号 |
| **无 Redis 时并发取号** | 数据库计数器靠行锁串行化（下面那条缺陷） |

**这个测试抓到过一个真缺陷**：`nextSeq` 在 Redis 不可用时自调用同类的 `nextSeqFromDb`，
而**自调用不走 Spring 代理**，`@Transactional` 形同虚设——`UPDATE` 与 `SELECT` 各自自动提交，
行锁在两条语句之间就释放了。并发下多个线程读到同一个值：
实测 200 次取号只有 **79 个不同值**（121 个重复）。

修法是拆出 `ConversationSeqCounter` 这个独立 bean（事务在它上面生效），
并把重试语义写清楚：`raiseSeqFloor` 只抬基线、**不白吃一个号**，
自愈之后的下一次取号必须是 `max(当前值, floor) + 1`。

这类缺陷单测抓不到（假 Redis/假 Mapper 验证的是假实现），
而且只在 Redis 抖动时才走到——所以它必须由真实并发来钉。

### 非分片表为什么可能全部"不存在"

应用只有一个数据源，就是 ShardingSphere 那个。所以"分片表 `message` 能写"
完全不代表"业务表 `conversation` 能读"——它们是两条不同的注册路径。

实测（5.5.3 + MySQL 8.4）发现：`!SINGLE` 规则**只写 `defaultDataSource`、不写 `tables`** 时，
所有非分片表一律不可访问：

```
TableNotFoundException: Table or view 'conversation' does not exist.
```

而 `message` 读写完全正常。这个组合极具误导性：错误全部指向业务表，
看起来像建表脚本没跑、连错库、或实体注解写错。根因在
`SingleTableDataNodeLoader.load` 的第一条分支（已反编译核实）：
**空列表既不是"全部"也不是"继承默认"，而是"一张都不要"**。

修法：`!SINGLE.tables: ["*.*"]`（未出现在分片规则里的表全部扫码登记）。
`tables` 的每一项还必须是**数据节点**格式（`ds_0.表名` 或 `*.*`），
裸表名会直接报 `InvalidDataNodeFormatException`。

这一条现在被三层钉住：

| 层 | 文件 | 盯什么 |
|---|---|---|
| 静态校验 | `tools/validate_yaml.py` | `tables` 非空、每项是数据节点、显式列表必须覆盖 DDL 里全部非分片表 |
| 单元级模板检查 | `ShardingSphereSpiAvailabilityTest` | 模板里 `!SINGLE` 规则必须真的能登记表（在 `mvn test` 里跑） |
| 真实服务 | `SingleTableRoutingIT` | 9 张非分片表逐个可查；`message` 可查；`message_0..15` **必须**被拒 |

`tools/mutate_sharding_deps.py` 里新增了两类变异（去掉 `tables` 列表 / 换成裸表名），
证明上面这些检查不是摆设，当前 **8/8 全部被捕获**。

### 消息写入（M3）：一条消息从帧到落库到推送

写路径集中在 `MessageService`（DESIGN §10.1），REST 与长连接<b>共用它</b>：

```
1. 参数与内容校验（msg_type / content 结构 / 长度）→ 40001/40002/40006/40007/40009
2. 权限：成员身份最先判，再按单聊/群聊走 MessageSendPolicy → 40303/40003/40304
3. 幂等短路：同 (convId, senderId, clientMsgId) 已存在 → 直接回执，**不取号**
4. 取 seq：Redis INCR（失败则落库兜底）
5. 落库：撞 (conv_id, seq) 主键 → 序号源自愈 + 重试
6. 扇出：≤ 500 人逐个推；大群只落库，客户端按 last_seq 拉
```

**为什么幂等短路必须排在第 4 步之前**：seq 是会被写进主键的离散量，取号即消耗。
若先取号再判重，客户端每次重试都会在会话里留下一个永久空洞——
「为什么少了几个号」将永远无法回答。

三处只在真实存储/真实并发下才会暴露的点，各自都有测试钉住：

| 断言 | 测试 | 手段 |
|---|---|---|
| 重放不消耗号（Redis 计数不前进）、不重复推送 | `MessageSendIT` | 真实 Redis + 真实 MySQL |
| Redis 被清空后序号从 1 重来 → 撞主键 → 自愈重试成功 | `MessageSendIT` | 手动删 `tm:seq:{convId}` |
| 各种非法输入的错误码，且**失败不留副作用**（不取号、不落库） | `MessageServiceTest` | 内存替身（穷举组合） |
| 帧字段原样翻译、错误码不被包装、发送者取自已鉴权会话 | `TwoClientEndToEndTest` | 真实 socket + 手写客户端 |

单测与集成测试的分工是刻意的：替身验证**规则**（哪个错误码、有没有副作用），
真库验证**规则在存储上的后果**（号有没有真的被消耗、碰撞后能不能真的插进去）。
两者能出的错完全不同——本轮就有一例：

> 单测把「非成员」与「好友关系」两个维度分开测时才发现，非成员发消息会先进入
> 「算单聊对方是谁」的分支，把「其余成员」算成两个人，于是报出 50000（会话成员数异常）
> 而不是 40303。修法是把成员身份提到最前面判——一句话的改动，
> 但只有把两个维度拆开测才看得见。

另记一条测试方法上的教训：`CMD_READ` 走业务线程池，而 `PING/PONG` 在 IO 线程上
被心跳处理器直接应答，两者**没有先后关系**。用「PONG 回来了」当作
「上一个命令已处理完」的证据会偶然失败（本轮先失败、重跑又绿）。
正确写法是带超时地等**副作用本身**（`awaitCondition`），而不是 `sleep` 一拍再赌。

集成测试的容器（`ItSpringConfig`）用 ShardingSphere 驱动 + HikariCP + MyBatis-Plus +
Lettuce 真实装配，并开 `@EnableTransactionManagement`——手工 `new` 出一个仓储
拿到的是「没有事务的版本」，正好会把上一节那个并发缺陷盖住。
这份容器以 test-jar 形式给 `tm-core` 复用：那套接法只应该有一份，
两份必然漂移（而「测试的接法与生产不一致」是最难发现的偏差）。

### 应用（M3）：从「能跑通」到「能启动」

M2/M3 的早期成果都是「测试跑得通」，而**应用根本起不来**——四个应用模块只有 `pom.xml`，
仓库里没有任何一个 `@SpringBootApplication`。补上入口类的第一件事就是写一个
**真实启动**的集成测试（`tm-app` 的 `AppHttpIT`）：`@SpringBootTest` 把
Tomcat + Netty + ShardingSphere + Redis + MyBatis-Plus + 全部配置类真启一遍，
然后用真实 HTTP 断言一遍鉴权全链路。

它第一次跑就拓出两个**应用根本起不来**的缺陷，而之前所有测试都是绿的：

| 缺陷 | 报错 | 根因 |
|---|---|---|
| `tm-channel` 的 `MessageMapper` 与 `tm-storage` 的 MyBatis `MessageMapper` 类名相同 | `ConflictingBeanDefinitionException: bean name 'messageMapper'` | 容器 Bean 名默认取类的短名 |
| test-jar 里的 `ItSpringConfig` 被扫进了生产容器 | `NoUniqueBeanDefinitionException: found 2: conversationRepositoryImpl, conversationRepositoryWithoutRedis` | 扫描根宽到 `com.tm.im`，测试配置也在其中 |

第二个缺陷是第一种「测试工具反过来影响生产代码」的形态，修法与理由写在
`TmAppApplication` 的类注释里（按包形状排除 `...it...`，而不是逐个类名列举）。

### 账号与令牌（M3）：一次性 refresh_token 存在哪里

`POST /v1/auth/{register,login,refresh,logout}` 与 `GET /v1/me` 已实现。
几处不显眼但一旦错了很难查的决定：

| 决定 | 不这么做会怎样 |
|---|---|
| 口令用 **PBKDF2-HMAC-SHA256**，迭代数写进哈希字符串（`pbkdf2-sha256$迭代数$盐$摘要`） | 迭代数写死在代码里：以后提高强度就等于把**所有人登出**。写进字符串则老哈希照旧能验，登录成功时顺手升级（`needsRehash`） |
| 登录失败一律 `40101`，且账号不存在时**也跑一次等价昂贵的哈希** | 用响应时间就能枚举系统中存在哪些 handle；区分错误码则直接告诉他们哪一半猜对了 |
| handle 先归一化（`strip()` + 小写）再比对 | 库里 `uk_handle` 用的是 `_ai_ci` 排序规则（大小写不敏感），不归一化就会出现「`existsHandle` 说没占用、`INSERT` 却撞唯一键」 |
| **refresh_token 存 Redis**（`tm:rt:{sha256}`，值里只有 actorId/设备标识）而不是新开一张表 | 存表要改 DDL 生成器、实体生成器、校验器三处；而它的生命周期天然是 TTL + 一次性，与表格格不入 |
| 「一次性」用 **`GETDEL`** 而不是 get + delete | 两个并发刷新请求会同时命中同一个凭证、各自换出一对新 token——这正是重放攻击的样子，而窗口只有微秒级，测试永远碰不到 |
| 刷新的调用路径**再查一次账号状态** | refresh_token 能活 30 天，不查则「封禁的最坏生效延迟」从 2 小时变成 30 天 |
| 字段名用 `spring.jackson.property-naming-strategy: SNAKE_CASE` 一处配置 | 逐字段写 `@JsonProperty` 的话，忘了那一个就静静变成 camelCase，客户端解析出一片 null |
| 未映射路由回 **HTTP 404 + code 40400 的统一信封** | Spring 默认的错误 JSON（`{"timestamp":...}`）会逼客户端写两套解析逻辑，而其中一套只在 URL 拼错时才走到 |
| 不用 CORS，改用 Vite 的 `server.proxy` | CORS 配错时 curl 全绿、只有浏览器红，而报错不指向任何一行服务端代码 |

存储层的 Redis 语义（TTL、`GETDEL`、坏值处理、**Redis 挂了回 50002 而不是「凭证无效」**）
由 `RedisRefreshTokenStoreIT` 在真实 Redis 上逐条钉住（9 个用例）。

### 会话与消息（M3）：REST 那一半

`POST /v1/conversations/direct`、`POST /v1/conversations/group`、
`GET /v1/conversations`、`GET /v1/conversations/{id}`、
`POST /v1/conversations/{id}/messages`、`GET /v1/conversations/{id}/messages`、
`POST /v1/conversations/{id}/read` 已实现（§4.1–§4.8）。写消息、上报已读、增量拉取
都**转调**长连接那条路径（`MessageService`），所以不会出现「REST 能发、长连接不能发」这种情况。

群成员管理（§4.9）也已实现：`POST`/`DELETE /v1/conversations/{id}/members`、
`DELETE .../members/me`、`PATCH /v1/conversations/{id}`、`PATCH .../members/{actor_id}`。
它的规则里最值得记的是**权限判据只有一条**：目标的角色码必须严格大于我的
（`1=OWNER` `2=ADMIN` `3=MEMBER`）——于是「没人能踢群主」与「两个 ADMIN 互相踢不动」
都是这条式子的推论，不需要为它们单独写规则，也不可能写出两套不一致的规则。

几处决定值得单独说，因为它们的「更简单写法」都能跑通、只是会错：

| 决定 | 不这么做会怎样 |
|---|---|
| 会话列表**全量取再按「最近活跃」排序**（每个会话一次「最新一条」点查），而不是先 `LIMIT 50` 再排 | 先截断会让「一年没说话、今天刚活跃」的会话从列表里消失——用户会以为消息丢了。真正的解是给 `conversation_member` 加一列 `last_activity_at`（改 DDL，见 DESIGN §14.1 的待办）；它到位之前选择「先正确、再优化」，并在会话数超过 200 时打 WARN |
| 游标带**类型与版本**（`{"v":1,"t":"msg","seq":7}`），而不是 base64 一个数字 | 两种游标（会话列表按时间、消息按 seq）长得一样时，**贴错不会报错**，只会返回一个内容不对的列表——而客户端绝不会怀疑是游标贴错了。它刻意**不签名**：伪造它拿不到任何数据（它只表达「从我自己列表的哪一项之后继续」） |
| 发消息的响应**全部来自落库后的那一行** | 用请求里的值拼响应，就一定会有一处漏掉某个字段（比如 `content` 用了请求的、`seq` 用了库里的）——而「幂等重放返回**完全相同**的响应」正是靠这一点在结构上成立，`ConversationHttpIT` 直接比较两次响应的**原始字节** |
| `SYSTEM` 消息**客户端一律 40302**，且这条规则写在 `MessageService` 里 | SYSTEM 豁免好友校验（§11.6），客户端能发它就有了一条绕道。规则放进 `SendCommand.fromClient` 之后，REST 与长连接两条入口都只需一处判断——两条入口各拦一道，迟早会有一道漏，而漏的那道正是攻击者选的那条 |
| 建群的系统消息**写失败不回滚** | 让一条通知决定建群成败，客户端会因为通知写不进去而重试建群，于是得到**两个群**；丢掉通知只表现为「这个群的第一条消息是空的」 |
| 建群用**一个仓储方法**在同一事务里写会话行 + 全部成员行 | 逐个写是 N+1 个事务，中途崩溃会留下「成员不全的群」，而客户端重试会建出第二个。`ConversationReadPathIT` 里有一条「成员行写失败则**会话行也不在**」的断言——它盯的是 `@Transactional` 是否真的经过代理生效（这个坑本项目在 `ConversationSeqCounter` 上踩过一次） |
| 已读上报回的是**生效后**的游标（重新读一次成员行），而不是请求里的值 | 仓储的更新是「只前进」的，客户端乱序上报一个小值时会话内已读是 7 而响应说 3，`unread_count` 凭空变大——而那正是用户唯一会看的那个数字 |
| 加人时**「已经在群里」不是错误**（列在 `already_members` 里），而踢人时「目标不在群里」是 `40908` | 两者看起来像同一件事，实际不是：一批人里有一个已在群里，不该让**另外几个**也加不进去（部分成功不能报错）；而踢人时目标不在，说明客户端的成员列表过期了（或 `actor_id` 拼错了）——静默成功会让一个拼错的 id 看起来像踢成功了 |
| 退群的通知**先写、成员行后删**；其余几个接口反过来 | `MessageService` 要求 SYSTEM 消息的作者当时是成员（否则客户端伪造系统消息那条防护就没了），而我一旦退群就不再是成员。反过来写的代价是那条通知**永远丢失**（重试仍然是先删） |
| 转让群主是**一个仓储方法里的三行写入**（新群主升 1、旧群主降 2、`owner_actor` 重指） | 只改前两行会留下**两个** OWNER，而权限判据读成员行——两个人于是都能转让、都能踢掉对方，任何一种顺序都无法收敛；只改第一行则表现为「群主退了，群却没主」 |

还有一个只在真实 HTTP 下才验得了的东西：`content` 出参必须是 **JSON 对象**（库里存的是 JSON 文本）。
MockMvc 里那层 Jackson 是测试自己装的，所以这条由 `ConversationHttpIT`（12 个用例，真实启动 + 真实 MySQL/Redis）钉住。
§4.9 另外单开了一个 `ConversationMemberHttpIT`（6 个用例），原因是它的两个接口用的是 `PATCH`，
而 `TestRestTemplate` 默认的 `SimpleClientHttpRequestFactory` 底层是 `HttpURLConnection`，
那个类**不认识 PATCH**（客户端还没发出去就 `ProtocolException: Invalid HTTP method: PATCH`）——
于是那个类把请求工厂换成 JDK 的 `HttpClient`。这也说明了一件事：
「服务端支持 PATCH」与「测试工具能发 PATCH」是两个独立的问题，前者的证据只有后者能提供。

成员管理的规则还额外做了一轮**变异测试**（`tools/mutate_member_rules.py`，15 个变异）：
把每条规则逐一改坏，看它该触发的用例是否必然失败。它们几乎全都属于
「改坏了照样能跑、而且看起来更正常」那一类——少一个等号（`<=` 写成 `<`）让两个 ADMIN 能互踢、
踢人时目标不在群里改成静默成功、把「写通知」与「删成员行」调个顺序、
转让时只改成员行不降旧群主（群里出现两个 OWNER）、去掉 `transferOwnership` 上的
`@Transactional`（失败时旧群主降了级、新群主却没升上去）——
这五条都不会报错，只会让群里慢慢变成一个谁也说不清权限状态的东西。
其中 14 条在 `verify_all.py` 里跑（`--unit-only`），最后一条要找真实 MySQL，在 `--services` 里。

**好友关系是「先写库造出来」的**：加好友的接口是 §3，还没做；而这些用例要验的是发送链路的权限规则，
不是加好友流程——所以每个用例自己注册一个「对手」并直接写一行 `friendship`，
而不是让两个功能纠缠在一起（否则失败时分不清是哪一半坏了）。

### Agent 管理（M8）：对等性是一条可执行的断言

`POST/GET/PATCH/DELETE /v1/agents`、`GET /v1/agents/{id}`、`POST .../rotate-key`
已实现（§7）。M8 的验收标准是「把用户端 H5 的调用换成 Agent SDK，
同一业务流程**只改认证头**即可跑通」——它现在是一条可执行的测试：
人类注册 → 创建 Agent（拿 api_key）→ **Agent 用 api_key 发好友请求** →
人类同意 → **Agent 用 api_key 发消息** → 人类读到了那条消息。
全程没有「Agent 专用接口」。

这不是靠一个兼容层做到的，而是结构上只有一条路：Agent 就是一行 `actor`
（`actor_type=2`），与人类走同一套 handle 规则（`AccountService.normalizeHandle`
被公开出来就是为这件事）、同一张凭据表、同一个鉴权入口、同一套协议。

几处必须想清楚的地方：

| 决定 | 不这么做会怎样 |
|---|---|
| `webhook_secret` 存**明文**、`api_key` 存哈希 | 前者要用于 HMAC 签名——**哈希不可逆，用它签出来的东西对方验不了**；后者服务端从不需要明文（鉴权是「拿哈希查表」）。把它们都存哈希是个「看起来更安全」的错误实现 |
| 停用 Agent 时**保留** api_key 的哈希（只删 webhook_secret） | 删掉哈希后，用旧 key 调用会得到 `40105`（动作是「检查或轮换」），而 Agent **无权**轮换（那要拥有者的凭证）——它只会一直重试一个永远不会成功的动作。保留哈希则鉴权能走到「账号已停用」那一步，回 `40301`（动作是「停止重试」）。这也与人类一致：封禁后密码本身没变，变的是账号状态 |
| 轮换**不连 webhook_secret 一起换** | 一次「换钥匙」不该让对方的回调验签全部失败（两者独立，02-auth.md §7 的安全建议里就是这么写的） |
| `push_mode` 必填、`WEBHOOK` 必须给 `endpoint_url`，且校验的是**合并后**的组合 | 默认成 `WEBHOOK` 会给一个没有地址的 Agent 一个永远投不出去的推送模式；只在创建时校验的话，一个 WS 模式的 Agent 可以被改成 WEBHOOK 而不给地址——表现是「消息发出去了、Agent 没收到、两边日志都干净」 |
| **不校验「只有人类能创建 Agent」** | `§11.5` 说「账号由人类创建」，但实现它要在领域层读 `actor_type` 做权限分支——DESIGN §0 的红线明确禁止（该字段只允许出现在「展示元数据」与「投递适配」两处）。允许一个 Agent 拥有另一个 Agent 的代价是责任链可能有两级，而 `owner_actor` 把它记下来了；开这个分支的代价是红线破一个口子 |
| `capabilities` 只是声明，不限制能力 | 声明了 `["text"]` 的 Agent 照样能发图片。能力是它**声明**的，不是平台**限制**的；让它变成限制需要一次显式的产品决策 |
| `GET /v1/agents` 返回**数组**而不是分页对象 | 一个账号最多 50 个 Agent，分页在这里只是多一层客户端要处理的形状 |

顺带修掉一个真缺陷（由 `AgentHttpIT` 的对等性验收抓到）：
`GET /v1/friends/requests` 的 `from_actor`/`to_actor` 曾经取的是 `actor_a`/`actor_b`
——而关系表那两列是**无序对**（约定 `actor_a < actor_b`），它们的名字描述的是
「哪个 id 小」而不是「谁先开口」。于是「接收人的 id 更小」（即先注册的人被后注册的人加）时
两个人会被静默对调。现在 from 取 `initiator`、to 取它的对面，
单测与端到端测试各钉了一遍。

验证：`AgentServiceTest`（12 用例，含「明文/哈希」与「停用保留哈希」两条关键取舍）、
`AgentHttpIT`（3 用例：对等性验收、拥有者权限与错误码、轮换与停用的可观察结果）。

### 好友（M5）：一次加好友只有一行记录

`POST /v1/friends/requests`、`.../accept`、`.../reject`、`GET /v1/friends/requests`、
`GET /v1/friends`、`DELETE /v1/friends/{id}`、`POST/DELETE /v1/friends/{id}/block`
已实现（§3.1–§3.6）。

最值得记的是**请求的生命周期与关系的状态在同一行上**（`friendship.status`）：从 PENDING 开始，
被接受后变成 ACCEPTED，或被删除（拒绝/过期）。方案对比：

| 做法 | 后果 |
|---|---|
| 「friend_request 表 + friendship 表」两张表 | 「请求被接受了」与「关系存在」是两次写入，中间任何一次失败或并发交错都会留下「请求说已接受、关系表说不是好友」——**用户明明同意了，对方却发不出消息**（40003），重试无法自愈 |
| 一行 + `status` | 只有一个地方能表示关系，不存在不一致的两份事实。代价是「拒绝」只能靠删除表达（没有「已拒绝」这个状态），而那正好是想要的：删除之后双方都能干净地重来 |

几处「更简单的写法能跑通、只是会错」的取舍：

| 决定 | 不这么做会怎样 |
|---|---|
| 过期的 PENDING **按不存在处理**（比较 `expires_at`），而不是先跑一个清理任务 | 清理任务带来一个新的失败模式：「任务没跑起来时行为就变了」。而覆盖它只需一次 writes——下一次同一对人再请求时 |
| 「重新发起」把那一行**整个换成新请求**（含 `request_id` / `created_at`） | 保留旧 `request_id` 时，响应会给客户端一个**库里不存在**的新 id，而用它去 accept 也能成功（按旧 id 也查得到）——两边各自“能用”，只是它们说的不是同一个请求。这条是 `FriendHttpIT` 跑出来的 |
| 同意**幂等**且返回同一个 `conv_id` | §3.5 的重试表把「同意好友请求」列为可安全重试；回一个「已经同意了」的错误会把一次成功的操作当成失败 |
| 删除时（拒绝）**不保留历史** | 保留就得回答「这行是待处理还是被拒过」，而被拒的一方重新发起时又要被区别对待 |
| 删好友**幂等**（`removed=false`），而踢群成员目标不在群里回 `40908` | 两者的客户端目标状态不同：踢人是「让这个人不在群里」，删好友的目标（我们不再是好友）已经成立了。而且删好友只能删自己的，不存在「拼错别人的 id 却以为删成功」 |
| 拉黑**复用同一行**改成 BLOCKED | 分两个概念（不再是好友 vs 不许加我）就得在每个读取点各自处理「他是好友但被拉黑了」这个组合 |
| 解除拉黑只删 BLOCKED 的行 | 当前是 ACCEPTED 时照删，会让一次「解除拉黑」悄悄删除一段真实的好友关系 |
| 列表分页的游标是 `(updated_at, request_id)`，而**不是**「对方的 actor_id」 | 关系存的是无序对，对方可能在 `actor_a` 也可能在 `actor_b`——同一个谓词在两个方向长得不一样，而分页谓词必须两边一致。`request_id` 是每一行自己的列（而且它是唯一索引），两个方向用同一个式子 |
| 分页谓词随手带上括号 | 生成器把 `AND (a OR b)` 写成 `AND a OR b` 时语义变成「满足 a 的全部，或满足 b 的全部」——一个看起来正常、只多返回一批数据的查询 |

**日配额分两档**（人类 50 / Agent 100，与 07-errors-limits §3.1 的维度表一致），
判据是 `actor_type`——这是「展示元数据之外的合法读取点」之一，
因为它决定的是**策略参数**（配额），而不是「谁有权限做什么」。

好友集缓存（DESIGN §11.6 里的 `tm:friend:*`）**刻意没做**：它要连带解决跨节点失效
（一个节点的缓存删不掉另一个节点的），而那是一个独立的问题；
当前的取舍是「每次回源数据库」——它一定正确。

验证：`FriendServiceTest`（21 用例，规则与错误码）+
`FriendHttpIT`（8 用例，真实 MySQL：分页不重不漏、游标贴错回 40010、
**删好友之后单聊真的发不出去（40003）而历史仍读得到**、拉黑是 40304 而不是 40003）。
后者是第一批「不再自己造 friendship 行」的消息类验证。

### 图片（M4）：两种「看起来更简单」的错做法

`POST /v1/media`、`GET /v1/media/{id}`（含 `?thumb=1`）已实现（§5）。
它的规则不多，但每一条都有一个「更简单、且更常见」的错误版本：

| 决定 | 不这么做会怎样 |
|---|---|
| 格式由**文件头**判断，`Content-Type` 与文件名完全不参与 | 按客户端声明回 `Content-Type` 的话，把 HTML 存成 `image/png` 就能让浏览器把同一段字节当 HTML 渲染（存储型 XSS）。下载时再补一个 `X-Content-Type-Options: nosniff` |
| 解码**之前**先用图片头里的宽高判断像素数（上限 4000 万） | 一个几十 KB 的 PNG 可以声明 5 万×5 万的画布，`ImageIO.read` 会真的去分配 10GB——体积校验（10MB）拦不住它。所以那道上限是内存护栏，刻意**不做成配置项** |
| 缩略图带**白底**重绘到 `TYPE_INT_RGB` 画布 | 直接把带透明通道的 PNG 交给 JPEG 编码器，`ImageIO` 抛 `IIOException: Bogus input colorspace`——一个只在「用户传了透明 PNG」时出现的 500 |
| 写入用**临时文件 + 原子改名** | 客户端拿到 `media_id` 会立刻 GET，直接写目标名会读到只写了一半的文件：HTTP 200 + 一个坏掉的图片，而日志里什么都没有 |
| 落库失败时**删掉刚写下的字节** | 上传接口返回 500 而盘上留下一个没有任何索引指向的文件——它永远不会被清理任务认领（清理按 `media` 表的 key 反查） |
| 容器（`multipart.max-file-size`）与应用（`tm.storage.max-size-bytes`）两处超限**都回 `40014`** | 容器那一层默认会被兜底处理器翻成 `50000`，而客户端对 50000 的动作是「退避重试」——重试一个超限的文件永远失败。反过来说，只留应用层那道就会让 1GB 的上传先被完整读进内存 |
| `thumb=1` 只认字面量 `1` | 宽容地接受 `true`/`yes` 会让真正生效的那一种写法变得不确定，而「我传了 thumb 却没生效」只会表现为「图有点大」 |

WebP 是**半支持**，且这件事是明说的：当前 JDK 的 ImageIO 没有 WebP 解码器，
所以 webp 能上传、能下载，但 `width`/`height` 为 `null`、不生成缩略图、
`thumb_url` 与 `url` 相同（`?thumb=1` 回原图）。另一条路是直接拒收它，
但 webp 在 §5.1 的白名单里——拒收一个文档允许的格式会让实现与文档对不上，
而「宽高为 null」是客户端本来就该处理的降级。

资源归属也是一个必须想清楚的地方：**下载不做「只有上传者能读」的校验**。
那张图会被发进会话，收件人必须能打开它；而「谁收到过这张图」需要反查 16 张消息分片表
（`content` 是 JSON，而不带 `conv_id` 的查询正是 DESIGN §8.7 明令禁止的广播）。
所以 `media_id` 在语义上就是**能力 URL**：雪花号、不可枚举，拿到它的人本来也已经
拿到了引用它的那条消息。代价是 id 泄露即长期可读，因此缓存策略写成不可变；
换成短时签名 URL 是另一件事（见 DESIGN §14.1 的待办）。

验证分三层：`MediaServiceTest`（22 个用例，含现场生成的解压炸弹 PNG——
真造一张 4 万×4 万的图要先吃掉 6GB 内存）、`LocalFsMediaStoreTest`（9 个用例，
重点是 key → 路径的穿越校验：`../`、反斜杠、`./../../` 各试一遍）、
`MediaHttpIT`（5 个用例，真实 multipart + 真实磁盘）。

### 广场（M7）：信息流为什么分两段读

`POST /v1/plaza/posts`、`GET /v1/plaza/feed`、`GET /v1/plaza/users/{id}/posts`、
`DELETE /v1/plaza/posts/{id}`、`POST/DELETE .../like`、`POST/GET .../comments`、
`DELETE /v1/plaza/comments/{id}` 已实现（§6.1–§6.6）。

**文档里的一处矛盾在实现时被迫解决**：DESIGN §11.3 把好友位放在时间位**之下**
（bit 19，于是它只能「同一秒内好友靠前」），而 `03-rest-api.md` §6.2 与
`01-concepts.md` §8 承诺的是「好友的动态整体优先，非好友的公开动态排在后面」。
两者不可能同时成立。选择是：**公式按 DESIGN 原样实现（游标依赖它），全局优先由读取路径实现**——
信息流分两段读，好友段（收件箱）读完再读公开段（非好友的 `PUBLIC` 动态），
游标里的类型标签（`pfeed` / `ppub`）记住读到哪一段。

| 决定 | 不这么做会怎样 |
|---|---|
| 发帖时**同步**写作者自己那一行收件箱，好友那部分才异步扩散 | 异步任务可被丢弃（队列满、停机），而「发完自己的动态就再也看不见」是不可接受的；「好友没收到」只是可补的缺口（公开流与作者个人页仍在） |
| 收件箱行里既有好友的、也有自己的，`is_friend_author` 用「作者不是我自己」判 | 把自己的动态标成「好友来源」会让客户端多一个徒标；而「自己的动态不进收件箱」就得在读的时候把两个来源按时间归并——排序规则被拆成两处 |
| 公开段排除「我的好友 + 我自己」 | 自己的每条公开动态会在信息流里出现两次（收件箱一次、公开流一次） |
| 分页游标从**数据库行**推进（而不是从「渲染成功的那些」） | 一页的最后几行动态被删时游标停在原地，永远翻不过去；`PlazaServiceTest` 里有一条用例专门钉住它 |
| 点赞计数是 `like_count = like_count + 1`（原子加减）+ 同事务写明细行 | 「读出来 +1 再写回」在并发下丢更新；而计数与明细分两个事务时会长期漂移，表现为「数字不太对」且无法复现 |
| 重复点赞靠**主键冲突**回 `40907`，取消点赞幂等 | 「先查再写」在双击下发会两次查不到、各插一行，而计数被加两次；取消点赞报错则会让重试的客户端以为没成功 |
| 发帖的 `client_post_id` 是**可选**幂等键（`uk_post_idem` 含 `author_id`） | 07-errors-limits §3.5 指出「发动态无幂等键 → 重试会重复」；不带键的调用方不受影响（NULL 不参与唯一性） |
| 删除动态**级联且在同一事务**（收件箱/点赞/评论） | 不做软化删除：`40404` 的语义就是「不存在」，而软化删除要求每个读路径都记得加那个条件——漏一处就是「已删除的动态还能被点赞」 |
| 图片归属在发帖时校验（图不存在或不是自己的 → `40008`） | 与发消息不同（那里刻意不查媒体表：热路径），发动态每天几十条，多一次点查换「不会引用到别人的图」是划算的 |
| `visibility` 同时接受 `PUBLIC`/`FRIENDS_ONLY` 与 `1`/`2` | 客户端把响应里的值原样回传是最省事的做法，拒绝它会让「读到的值不能写回去」变成一个坑 |

**已知欠账（写在代码里，不是静默行为）**：好友数超过
`tm.feed.celebrity-threshold` 的作者会**跳过写扩散**并记 WARN，
而 DESIGN §11.3 说的「改读扩散」尚未实现——那些动态仍会出现在
公开流与作者个人页，但不会进其好友的信息流。

验证：`PlazaServiceTest`（23 用例：配额两档、幂等不吃配额、大 V 跳过时自己的行仍在、
队列被拒不影响发帖、仅好友可见不外泄、两段拼接翻页不重不漏、删除级联、点赞计数恰好一次、
评论权限与回复校验）+ `PlazaHttpIT`（9 用例，真实 MySQL：DDL 真的存在、
主键冲突真的回 40907、`like_count` 真的原子加减、异步扩散真的落到好友收件箱、
翻页不重不漏、游标贴错回 40010）。

### 管理后台（M9）：为什么它必须是另一个 JAR

后台 API 的每一个接口都能「封任何人的号、删任何一条动态」。这类能力不能靠约定保护，
只能靠**边界**：`tm-admin.jar` 是独立应用、独立端口，与 `tm-app.jar` 不共进程。
理由很具体——用户端的每一处输入解析、每一个第三方依赖都在那个进程里，
管理接口与它同进程，等于把这些面全部加成「封人权」的入口。

由此往下是一串连带决定：

| 决定 | 不这么做会怎样 |
|---|---|
| 后台会话是**库里的行**（`admin_session`，只存 `token_hash`，8 小时），不是 JWT | JWT 无状态、**删不掉**：停用一个后台账号后，他的旧凭证还能继续封号到过期为止 |
| 凭证前缀 `adm_`（用户 JWT 无前缀、Agent 是 `sk_`） | 三种凭证形状相近；前缀是日志里唯一能一眼分辨的东西，也是 40102 与 40101 的分界线 |
| `admin_user` 与 `actor` 是**两张互不相干的表** | 后台不参与会话/好友/消息；把管理员塞进 `actor` 后，「列全站用户」的接口会顺手把后台账号列出去，而审计里的 `admin_id` 也指向了「某个人」 |
| 失败计数与锁定时间落在**库**里（`failed_attempts` / `locked_until`） | 放在单节点内存里 = 多实例部署时每个实例各给 5 次机会，而单实例开发时看不出来 |
| 登录失败走 `@Transactional(noRollbackFor = TmException.class)` | 默认回滚会把「失败计数 + 失败审计」一起删掉：错误码一个不少、用例全绿，**而锁定永远不会触发** |
| 权限判据写在 `AdminService` 里（`requireSuper`）而不是各控制器里 | 控制器里的判断会随接口数量增长而漏写一个；而「漏写的那一个」恰好就是越权入口 |
| 每一个写动作与业务变更**同事务**写 `admin_audit_log` | 「先改库、再记日志」中间失败时，事后只能靠「谁可能知道口令」来推断 |
| 首个账号由 `tm.admin.bootstrap.*` 在**表为空时**生效一次 | 否则得往仓库里放一份带口令的建号脚本；一次性口令即使泄漏也没有可用的时间窗口 |
| 删帖复用作者删帖那条级联路径（`PostDeletionPort`，后台不依赖整个 `PlazaService`） | 后台自己再实现一遍删除，就会出现两套删除语义：一处清了收件箱，另一处没清 |

**一个真实的缺陷**（不是设计问题，是编码问题）：失败计数与失败审计写在抛出 `40101` 之前，
而 Spring 默认对运行时异常回滚——登录失败时那两处写入被一起回滚掉了。
表现是「错误码完全正确、用例全绿、锁定永远不触发」。抓到它的不是断言返回值的用例，
而是 `AdminHttpIT` 里**数库里的行**的那一条。

验证：`AdminServiceTest`（27 用例：会话过期/已登出/拿 JWT 贴后台、锁定与计数、审计四角度、
停用连带删会话、bootstrap 三条路径）+ `AdminApiContractTest`（18 用例，standalone MockMvc +
真实 `AdminService`：信封、错误码、码值越界回 40002、分页夹取与游标、snake_case）+
`AdminHttpIT`（12 用例，真实 HTTP + 真实 MySQL：独立起进程、封禁写的是用户端鉴权读的同一列、
停用立刻踢会话、删帖级联、审计可查）+ `mutate_admin_api.py`（6 个变异，包括「删掉权限判断」
与「停用时不删会话」，全部被捕获）。

### 断点续传读取（M3）：`CMD_SYNC` 怎么落地

协议冻结之后剩下的就是那条「按 `(conv_id, since_seq)` 分页拉消息」的路径（DESIGN §10.2）。
它看起来只是一个 `SELECT ... WHERE seq > ? LIMIT ?`，但真正的决策全在边界上：

| 决策 | 如果不这样做 |
|---|---|
| `has_more` 靠**每会话多取一行**得出 | 先查 `max_seq` 再比大小要多一次查询，而两次查询之间新插一条消息就会把它误判成 true —— 一个只在并发下出现、本地永远复现不了的「多一轮拉取」 |
| `has_more=true` 时**不发** `SYNC_END` | 客户端会以为已追平并开始收实时推送，中间缺的那段永久留在本地 |
| 非成员/已不存在的会话游标**跳过**，并在 `SYNC_END.message` 里点名 | 回 40303 的话，一个已退群的游标会让该用户**永远补不了其他会话**的消息（而修它的办法恰好要先跑通 SYNC）；静默跳过是另一个极端——客户端永远带着一个无效游标重连而无人知道 |
| `limit` 收敛到 `maxPullSize`，**游标个数**另设上限 | 只限其中之一不够：前者拦住单个大群，后者拦住「有 5000 个会话的客户端用一帧换来 5000 次查询」。限流器数的是帧数，拦不住这个 |
| `truncated` 不从「某一行不存在」反推，因此目前恒为 false | `seq` 允许有空洞（取号即消耗），「缺行」无法区分「从未产生」与「已被清理」；把它当截断会让客户端做一次拿到同样数据的 REST 重拉。等归档任务给出水位再置位 |
| 逐游标顺序查，不并行 | 业务线程池的并发度就是背压；并行化把背压从连接级搬到数据库连接池，池满后受影响的是全体用户 |

还有一个只在真连接上才看得见的形状：**每会话多取的那一行、以及“本轮没什么要补”的那一帧，
在线上是零字节载荷**（proto3 不编码默认值）。客户端把它当解析失败的话，会在最正常的场景下报错——
所以它由 `TwoClientEndToEndTest.syncReportsSkippedCursorsInTheEndFrame` 钉住，
而不只是写在文档里。

**验收标准是「一条不漏、一条不重、必然终止」**，所以集成测试跑的是真实多轮循环而不是单次调用：
`MessageSyncIT.pagingRoundsTerminateAndDeliverEveryMessageExactlyOnce` 用 `limit=2` 拉 5 条消息，
断言恰好 3 轮、拿到 `1,2,3,4,5`，并且**每一轮 `has_more=true` 时都至少有一条消息**（否则客户端推不动游标）。

这些规则都属「改坏了照样能跑、而且看起来更正常」的一类：少取一行不报错、多回一帧不报错、
失败时静默回一帧空结果更不报错。因此它们由 `tools/mutate_sync_read_path.py` 逐条证明——
**10 个变异全部被捕获**（少取一行 / `has_more=true` 也发 END / 失败静默 / 两帧顺序反 /
END 的 `req_id` 写 0 / 跳过不点名 / 游标上限失效 / limit 不收敛 / …），
而且脚本要求「由**指定的那个用例**失败」，用例被改名或删掉都会直接报错。

### 命令字契约：为什么 `CMD_SYNC` 的响应要另占一个编号

协议里 `payload` 是 `bytes`，**类型信息完全由 `cmd` 决定**，没有第二条线索。
早期版本里 `CMD_SYNC = 14` 同时承担了两个方向的两种载荷：

```
C→S  payload = SyncRequest     // 「我要补消息」
S→C  payload = SyncResponse    // 「给你补消息」
```

同一条连接上收到 `cmd=14`，接收方没有任何依据判断该按哪个类型解；而 protobuf
**不会报错**（两者都含嵌套消息字段，只是字段号不同）：不匹配的字段被当未知字段跳过，
返回一个「解析成功」的对象。于是这一条命令一直没有被实现——它回 50000（不静默，
因为静默会让客户端一直等响应，现象是「消息发出去没反应」，看起来像丢包），
而不是在这里偷偷挑一个类型去解。

修法是把响应拆成独立编号 **`CMD_SYNC_RESP = 16`**，而不是把 `CMD_SYNC_END(15)`
挪到 16 去腾位置：15 已经写在对外文档里，重排会让任何一份按文档实现的客户端静默错位，
而追加新编号的代价只是表格里多一行（接入文档 §2.3 的「三条不变量」）。

修正涉及三处**手写的**文件，它们之间没有任何编译期约束，因此每一条都有机器拦截：

| 要防的事 | 拦它的东西 |
|---|---|
| 新增命令字时漏声明方向/载荷 | proto 枚举行格式写死，`mutate-cmd` 里「漏写方向」这个变异必被捕获 |
| 三处写得不一致（方向写反、编号写错、漏登记） | `tools/verify_integration_docs.py` 第 5 节：proto ↔ 文档 §2.1 ↔ 服务端 `Frames` 三方逐项对比 |
| 服务端忘了给新命令字加分支 | `Frames` 的 switch **不写 default** → javac 直接报「switch 表达式没有覆盖所有可能的输入值」 |
| 客户端把收到的帧回显（很常见） | `BusinessHandler` 的方向门禁 → 40000 并点明「`CMD_PUSH` 是服务端专用命令」，而不是含混的 `unsupported cmd` |
| 校验器本身是摆设 | `tools/mutate_command_contract.py`：10 个变异（方向写反 / 编号写错 / 漏一行 / 漏登记 / 少写方向…）逐个必须被捕获，10/10 |

本轮为后三条做了**负面证明**（临时改坏 → 观察失败 → 还原并核对字节）：

- 去掉 `Frames.expectedBodyType` 里 `CMD_SYNC_RESP` 那一行 → `mvn compile` 失败于
  `switch 表达式没有覆盖所有可能的输入值`；去掉 `Frames.direction` 里的同样一行 → 同样编译失败。
- 去掉 `BusinessHandler` 的方向门禁 → `serverOnlyCommandsAreRejectedAsClientMistakes`
  立刻失败（`expected: 40000 but was: 50000`），改回即绿。

> 两个 40000 的区别是有意设计的：命令字**只由服务端发**、还是**根本不存在**。
> 把两者混成一句 `unsupported cmd`，会把排查方向指向「服务端不认识这个命令」，正好指反。
> 而如果是「协议允许客户端发、但分发里忘了写分支」，那属于服务端自己的疏漏，
> 回的是 50000（用 40000 会是撒谎，静默会是灾难）。

**接下去仍未做的事**：归档/清理任务还没做，所以 `SyncResponse.truncated` 目前恒为 `false`
（§6.4 解释了为什么不猜）。

### 跳节点推送：为什么它只能是 fire-and-forget

`tm:push:{nodeId}`（DESIGN §7.4）此前只完成了一半（路由与探活），这里补上了另一半。
发送路径现在是「**先本地、再跨节点**」：本地连接表命中则直写；否则查 `tm:route:{actorId}`，
指向别的节点就把**已经编好的整帧**（base64）发到那个节点的频道上，只有它订阅。

四件必须想清楚的事：

| 决定 | 不这么做会怎样 |
|---|---|
| 频道按**节点**分，而不是一个共享队列 | 共享队列需要消费组、重试、死信——而那一切都是为了「不丢」，可这里**丢了正好**：消息已落库，对方重连时按 `last_seq` 走 SYNC 补齐。把「可丢」包装成「必须不丢」只会凭空多出一堆运维面 |
| 先查本地表、再查路由 | 反过来的话，在「路由还没发布完（或发布失败）但连接已经在本地表」的窗口里会把消息发给另一个节点——而对方明明就在本地 |
| 「路由指向本节点却没有连接」也返回 0 | 那是一条陈旧路由（连接已断、解绑失败），向自己投一帧只会得到一次无用往返与一条「收到发给自己的推送」的日志 |
| 订阅相位 **400** < 探活 **500** < Netty **1000** | 订阅晚于「宣布本节点存活」时，从宣布到订阅之间投过来的帧全部丢失——发布方报成功、接收方什么也没收到，两边日志都干净 |
| 读不懂的载荷只跳过并计数 | 滚动升级期间频道上会有别的版本的消息，而一条读不懂的消息让监听线程死掉，后果是**本节点再也收不到任何跨节点推送**（一个节点级的功能静默消失） |

载荷是 `{"actorId":..,"from":..,"frame":"<base64 的整帧>"}`：直接传编好的帧，
而不是列字段。列字段意味着两边各写一遍「字段 → 帧」的映射，而漂移的表现是
「本机推送正常、跨节点推送缺字段」——只有多实例部署时才会出现。
代价是 base64 把体积放大约 1/3（在 Pub/Sub 的量级上可忽略）。

验证：`ClusterMessagePushPortTest`（6 用例，网关的四条决策，含一条**真实**的本地命中——
用 `EmbeddedChannel` 注册一条连接，而不是伪造「命中」）、
`PushBusRedisIT`（4 用例，真实 Redis：两个节点各自的订阅、只有目标收到、帧逐字节相同、
脏载荷只跳过、目标不在线时发布照样成功）、`RedisPushBusPhaseTest`（相位三者的顺序），
并将 4 条新规则接进了 `mutate_cluster_routing.py`（**17 个变异全部被捕获**，
其中 6 条需要真实 Redis，跑在 `verify_all --services` 里）。

REST 侧已做完「能登录」「能开会话、发消息、翻历史、管成员、收发图片」「加好友」
「Agent 管理」「广场（信息流 / 点赞 / 评论）」（见 README 的「账号与令牌」「会话与消息」
「好友」「图片」「Agent」「广场」六节），
还没做的是：§2.2–§2.4 的几个读接口与改资料、Agent 的 Webhook 主动投递、
大 V 的读扩散（见「广场」一节的欠账），
以及用户端 Vue 脚手架（M3 验收标准的最后一步）。

> `docs/integration/03-rest-api.md` 里与代码对齐过的地方（已改文档而非改代码）：
> ① §4.5 的错误表原写「`40400` 会话不存在」与「`40302` 已被对方拉黑」，而实现回的是
> `40402`、`40304`——那是文档在错误码细分（`40401`–`40404`、`40304`）之前写的，属于文案漂移，
> 「40400 还是 40402」这种差别恰好最容易让排查方向指错地方；
> ② §4.2/§4.3/§4.4 的示例里补上了 `title`/`member_count`，并给 §4.2 补上了它从来没有过的错误表；
> ③ 新增 §1.7 把「`@handle` 与纯数字 actor_id」两种写法写成契约：不带 `@` 的字符串一律
> `40002`，因为 handle 允许数字（`^[A-Za-z0-9_]{3,32}$`），`"1001"` 猜错的后果是
> 「消息发给了另一个人」，重试也修不回来；
> ④ §4.9 曾标着「未实现（M4）」——那是对的（当时它没有路由，返回 `404`），
> 但后来它只在文档里停了很久；实现之后这一节被换成了完整契约（权限矩阵、五个路由、
> 逐接口的请求/响应示例、错误码表、`action` 取值表）。

> 一处已知的不一致：`conversation` 表没有 `notice`（群公告）列，所以 §4.2/§4.4 的 `notice`
> 字段没有实现，文档已同步删掉它。§4.9 的 `PATCH /v1/conversations/{id}` 因此**只改群名**。
> 真要做公告时它应该就是这个接口上的一个**可改字段**（而不是建群时的一次性输入），
> 而那需要一次 DDL 变更（生成器 + 实体 + 迁移），所以不在这一轮里。

### SPI 守卫：为什么会有这么一个测试

`ShardingSphereSpiAvailabilityTest` 在默认的 `mvn test` 里跑，不需要数据库。
它断言 shardingsphere 运行所需的 SPI 实现在 classpath 上都存在。

理由很具体：**5.5.3 的 `shardingsphere-jdbc` 只是个门面**，分片、单机模式、
连接池元数据、SQL 方言解析器、URL 加载器全都要显式声明依赖。
漏掉任何一个，报错都不指向缺依赖：

| 漏掉的依赖 | 你看到的报错 | 像是 |
|---|---|---|
| `shardingsphere-sharding-core` | `Invalid tag: !SHARDING` | YAML 写错了 |
| `shardingsphere-infra-data-source-pool-hikari` | `NullPointerException @ StorageUnit` | 框架有 bug |
| `shardingsphere-standalone-mode-core` | `SPI-00001: ... ContextManagerBuilder with type 'null'` | 配置缺了 mode |
| `shardingsphere-parser-sql-engine-mysql` | 找不到 `SQLParserEngine type=MySQL` | 没提"方言"二字 |

这四个症状本轮**逐个真实出现过**。所以干脆直接把"SPI 有没有实现"断言出来。
细节与完整清单见 `docs/DESIGN.md` §3.3（陷阱 3）。

这个守卫本身也做变异测试，证明它不是摆设：

```bash
uv run python tools/mutate_sharding_deps.py
```

它把工程拷到临时目录，逐个删掉真实依赖并断言守卫**失败且诊断可读**，
仓库本体全程只读（结束后核对哈希）。不直接改真 pom 是因为：
一旦进程被强杀（超时、Ctrl+C），残缺的 pom 会比不做变异测试更糟。

### 排序规则为什么必须显式写

当前目标实例是 **MySQL 8.4.4（端口 13306）**；旧的 5.7.44 实例仍在 3306，但已不再使用。只写 `DEFAULT CHARSET=utf8mb4` 而不写 `COLLATE` 时，
排序规则取"该字符集的默认值"，而这个默认值随服务端**版本与配置**变化：

| 服务端 | `CHARSET=utf8mb4` 的默认排序规则 |
|---|---|
| MySQL 5.7 | `utf8mb4_general_ci` |
| MySQL 8.0+ | `utf8mb4_0900_ai_ci` |

后果是同一份脚本在不同环境建出的表排序规则不同，跨表 JOIN 可能报
`Illegal mix of collations`，迁移时出现难以定位的排序差异。因此 25 张表
统一显式声明排序规则，并且 `verify_schema.py` 会断言**全库只有一种规则**——
混用比用错更难查。

当前使用 `utf8mb4_0900_ai_ci`。目标实例已从 5.7（`3306`）迁至
**MySQL 8.4.4（`13306`）**，原先为兼容 5.7 而选 `utf8mb4_unicode_ci` 的理由已消失：

| 排序规则 | UCA 版本 | 5.7 可用 | 备注 |
|---|---|---|---|
| `utf8mb4_general_ci` | 最早 | ✅ | 连 `cafe` 与 `café` 都视为相等，已列入拒绝名单 |
| `utf8mb4_unicode_ci` | 4.0.0 | ✅ | 为 5.7 兼容保留的备选 |
| `utf8mb4_0900_ai_ci` | 9.0.0 | ❌ | **当前选择**；消息正文是自由文本，需要现代 UCA |

等价语义差异不靠记忆判断：`tools/probe_collation.py` 在**实际实例上**逐对实测
（`uv run --with pymysql python tools/probe_collation.py`）：

```
  排序规则              仅大小写不同   重音差异   ss/ß 折叠   ae/æ 折叠
  utf8mb4_unicode_ci   相等          相等       相等        不等
  utf8mb4_0900_ai_ci   相等          相等       相等        相等
```

这些差异看起来会让 `handle` 唯一索引变得危险（`strasse` 与 `straße` 会撞），
但 handle 的校验规则是 3-32 位字母数字下划线（`docs/integration/02-auth.md`，
错误码 `40004`），即**纯 ASCII**。非 ASCII 的 handle 根本进不了库，
所以这些差异对 `uk_handle` 不产生任何实际影响。

换环境时用 `--collation` 重新生成（例如拿到 5.7 实例）：

```bash
uv run python tools/gen_schema.py --collation utf8mb4_unicode_ci   # 回到 5.7 可用的规则
```

> 客户端配置同理：`deploy/conf/local-conn.env` 的 `MYSQL_PORT` 必须指向 8.4 实例（`13306`）。
> 若误指回 `3306`，`bootstrap_db.py` 会在建表前就报出“服务端不支持该排序规则”，
> 而不是等到第一条 `CREATE TABLE` 才抛 `ERROR 1273`。

### 服务端真实版本怎么确认

**`mysql --version` 输出的是客户端版本，与所连服务端无关。**
客户端 8.0 连 5.7 服务端是完全正常的组合，这是版本误判的头号原因。

判断服务端版本必须用 `SELECT VERSION()`。`tools/verify_server.sql` 在服务端侧
一次性给出全部事实（版本、排序规则、账号、权限、监听地址、InnoDB）：

```bash
mysql -h 127.0.0.1 -u root -p < tools/verify_server.sql
```

从**外部**也能判断，且不需要登录——`tools/diag_conn.py` 会解析服务端握手包，
三条互相独立的证据：

| 证据 | MySQL 5.7 | MySQL 8.0 |
|---|---|---|
| 握手包版本字符串 | `5.7.x` | `8.0.x` |
| 握手包 collation id | `45` = `utf8mb4_general_ci` | `255` = `utf8mb4_0900_ai_ci` |
| 8.0 专有 capability 位 | 均未设置 | 设置 |

> 不要用 `CLIENT_DEPRECATE_EOF`（`0x01000000`）判别——该位从 **MySQL 5.7.5** 起
> 就会设置，5.7.44 设置它是正常的，不是 8.0 特征。
> 真正只属 8.0 的位：`QUERY_ATTRIBUTES`（8.0.23+）、`OPTIONAL_RESULTSET_METADATA`、
> `ZSTD_COMPRESSION_ALGORITHM`（8.0.18+）。

## 配置

外部 MySQL / Redis 连接信息**不入库**。见 [deploy/conf/](deploy/conf/) 模板：

```bash
cp deploy/conf/sharding.yaml.example deploy/conf/sharding.yaml
cp deploy/conf/application-external.yml.example deploy/conf/application-external.yml
# 填入真实连接信息后启动
java -jar tm-app.jar \
  --spring.config.additional-location=file:./deploy/conf/ \
  --sharding.url=jdbc:shardingsphere:absolutepath:/etc/tm/sharding.yaml
```

## M0 前置检查

开工前先验证外部 MySQL / Redis 是否真能连上（**只读探测，默认不做任何写操作**）：

```bash
# 1. 填空
copy deploy\conf\local-conn.env.example deploy\conf\local-conn.env

# 2. 检查
uv run --with pymysql --with redis python tools/check_services.py
```

会验证：TCP 可达性、MySQL 认证与版本、InnoDB / 建表权限 / 时区 / 隔离级别、
Redis 版本与 `INCR`（seq 生成）、`PUBLISH`（跨节点推送）等本方案强依赖的能力。

连接失败时用 `tools/diag_conn.py` 定位**为什么**失败——它区分四类性质完全不同的问题：

```bash
uv run --with pymysql --with cryptography python tools/diag_conn.py
```

| 现象 | 含义 | 该做什么 |
|---|---|---|
| 基线（RFC5737 保留段）也"可连" | 本机网络有透明代理 | TCP 探测不可信，只认协议层握手 |
| TCP 超时（无响应） | 端口被防火墙过滤，或服务只 bind 127.0.0.1 | 查安全组 / `bind` 配置 |
| TCP 立即 refused | 该地址上确实没有服务监听 | 启动服务或改 `bind 0.0.0.0` |
| 拿到真实握手包但认证被拒 | **网络没问题，是凭据/账号问题** | MySQL `1130`=IP 未授权；`1045`=密码或用户名错 |

### 本机网络不可信时：外部视角验证

本机装有 **Sangfor aTrust**（零信任 SASE）与 **v2ray**，出站流量被接管。
实测：连 RFC5737 保留段 `192.0.2.1:1`（公网必不可能路由）都能"连上"。

更麻烦的是，仅看本机结果**无法区分**"远端拒绝"与"本机拦截"。
所以工具在检出端口级失败时，会**自动**借 [check-host.net](https://check-host.net)
的全球节点从完全独立的视角复核（同时带上一个已知可用的对照端口）：

```
—— 以下为 2026-09 修复前的实测输出，保留作为方法演示 ——
── 端口 3306 ──  14/14 节点可连     ← 对照：同主机的 MySQL
── 端口 6379 ──   0/14 节点可连     ← 11 个节点明确收到 Connection refused

[FAIL] 端口 6379: 全球节点无一可连，而同主机 [3306] 正常
         → 该端口在远端确实没有监听（不是本机网络问题）
```

奥地利、加拿大、伊朗、以色列、葡萄牙、俄罗斯、新加坡、英国、乌克兰的服务器
**全都收到 RST**，这才排除了本机干扰。加 `--no-external` 可关闭此步骤。

> **后续**：服务器侧重启后 Redis 已正常监听，当前 `AUTH` + `PING` 均通过
> （Redis 7.4.2）。这段输出现在只剩“方法可复现”的价值，不再代表当前状态。

> 注意：`v2ray` 的 SOCKS 代理**不能**用来验证连通性。实测它对
> 确定关闭的端口（`1433`/`9999`/随机高位端口）也立即返回 `CONNECTED`（0-18ms），
> 是本地乐观接受，完全不可信。

两个容易被忽略的点，脚本已内置处理：

- **透明拦截**：先用 RFC5737 保留段（`192.0.2.1:1`，公网必不可能路由）做基线。
  若基线也报"可连"，说明本机网络存在透明代理，此时 **TCP 可达不能证明服务存在**，
  脚本会把 TCP 结果降级为警告，只以协议层握手（MySQL 认证 / Redis PING）作为通过依据。
- **密码泄漏**：所有输出中密码自动脱敏，异常信息中的回显也会被替换。

#### 同一个拦截的另一个后果：`git push` 到 GitHub

`github.com` 在本机解析到 `20.205.243.166`（GitHub 的亚太入口），该 IP 的 443
被中途阻断（`git push` 报 `Recv failure: Connection was reset`），而 GitHub 的
其它入口 IP（如 `140.82.113.3`）是通的。**不是 GitHub 不可达，是 DNS 选中的那个
IP 不通**——所以诊断顺序是「按 IP 逐个测 443」，而不是先怀疑凭据或代理。

本仓库的本地配置钉住了其中一个可用 IP（只影响这个仓库）：

```bash
git config http.curloptResolve github.com:443:140.82.113.3
```

该 IP 失效时，`Test-NetConnection <ip> -Port 443` 换一个通的即可。

## M0 建库建表

```bash
# 1. 先看要执行什么（不连库）
python tools/bootstrap_db.py --dry-run

# 2. 真正执行（建库 → 25 张表 → 验证 → 冒烟测试）
uv run --with pymysql python tools/bootstrap_db.py --apply

# 3. 只验证已有库
uv run --with pymysql python tools/bootstrap_db.py --verify-only
```

冒烟测试会在**事务内**写入并回滚，验证这些事：

- 人与 Agent 写入**同一张 `actor` 表**（对等模型的物理验证）
- `conv_id=100` 按 `%16` 路由到 `message_4`
- 重复 `(conv_id, sender_id, client_msg_id)` **被唯一键拒绝**
- 同 `(conv_id, seq)` 重复插入被主键拒绝
- 中文与 emoji 🎉 往返无损（utf8mb4 真的生效）
- `content->>'$.text'` JSON 提取正常

全程**不做 DROP / TRUNCATE / DELETE**。

## License

MIT
