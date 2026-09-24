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
deploy/   配置模板（sharding.yaml / application-external.yml）
deploy/sql/  建表脚本（由 tools/gen_schema.py 生成，勿手改）
proto/    长连接协议定义（transport.proto，可直接 protoc 编译）
tools/    校验、生成与引导脚本
server/   后端（规划中）
web/      前端（规划中）
sdk/      Agent SDK（规划中）
```

## 自检

一条命令跑完所有验证：

```bash
uv run --with protobuf --with pyyaml --with sqlglot python tools/verify_all.py
```

| 检查项 | 内容 |
|---|---|
| `schema` | 建表 SQL 语法 + **分片语义约束**（主键/幂等键必须含分片列） |
| `server-sql` | 服务端核验脚本的语法与 information_schema 列引用 |
| `docs` | 接入文档字节样本与签名向量 |
| `shard` | 分片口径三方一致（DDL ↔ 模板 ↔ 代码）+ 配置可解析 + **`!SINGLE` 单表规则必须非空** |
| `errors` | 错误码契约：文档 §2 ↔ 枚举 |
| `entities` | 实体类与建表 SQL 一致 |
| `samples` | Webhook 签名测试向量 |
| `config-tmpl` | 配置模板与 `@ConfigurationProperties` 一致（缺项 / 拼错 / 默认值不一致） |
| `mutate` | **变异测试**：验证建表校验器真的能抓到错误 |
| `mutate-deps` | **变异测试**：验证 ShardingSphere 依赖缺失与配置陷阱必被守卫捕获（8 类） |
| `mutate-cfg` | **变异测试**：验证模板校验器能抓到 5 类缺陷 |

`--quick` 跳过耗时的 `docs` / `mutate` / `mutate-deps` / `mutate-cfg`。

另有 2 项需要外部 MySQL/Redis（`runtime-cfg` = 运行时配置是否与当前
`local-conn.env` 一致，`collation` = 实测服务端排序规则等价性），用 `--services` 打开：

```bash
uv run --with protobuf --with pyyaml --with sqlglot --with pymysql \
  python tools/verify_all.py --services
```

不加 `--services` 时脚本会明确打印「跳过了 2 项需要外部服务的检查」——
而不是让读者以为绿色代表全都验过了。

### 建表 SQL 为什么是生成的

`deploy/sql/01-schema.sql` **不要手工编辑**。16 张分片表结构必须完全一致，
手写 16 遍必然漂移，而漂移在 ShardingSphere 下不会报错，只会在某个分片上静默丢约束。

```bash
python tools/gen_schema.py              # 重新生成
python tools/gen_schema.py --check      # 校验已生成文件是否为当前定义
uv run --with sqlglot python tools/verify_schema.py   # 深度校验
```

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

### 服务端测试：单元 / 集成两层

```bash
# 单元测试：离线可跑，不需要数据库与凭据
mvn -o test

# 集成测试：需要真实 MySQL 与 Redis，用 -Pit 显式激活
uv run python tools/gen_runtime_config.py
mvn -pl tm-storage -am test -Pit \
  "-Dtm.it.config=deploy/conf/runtime/sharding.yaml" \
  "-Dtm.it.properties=deploy/conf/runtime/it.properties"
```

集成测试**刻意做成"缺配置就失败"**，而不是"缺配置就跳过"：
跳过会让"没验证"与"验证通过"呈现同一个绿色。

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
