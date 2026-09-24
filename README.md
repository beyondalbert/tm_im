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
| `shard` | 分片口径三方一致（DDL ↔ 模板 ↔ 代码）+ 配置可解析 |
| `errors` | 错误码契约：文档 §2 ↔ 枚举 |
| `entities` | 实体类与建表 SQL 一致 |
| `samples` | Webhook 签名测试向量 |
| `mutate` | **变异测试**：验证建表校验器真的能抓到错误 |
| `mutate-deps` | **变异测试**：验证 ShardingSphere 依赖缺失必被守卫捕获 |

`--quick` 跳过耗时的 `docs` / `mutate` / `mutate-deps`。

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

### 服务端测试：单元 / 集成两层

```bash
# 单元测试：离线可跑，不需要数据库与凭据
mvn -o test

# 集成测试：需要真实 MySQL，用 -Pit 显式激活
uv run python tools/gen_runtime_config.py
mvn -pl tm-storage -am test -Pit "-Dtm.it.config=deploy/conf/runtime/sharding.yaml"
```

集成测试**刻意做成"缺配置就失败"**，而不是"缺配置就跳过"：
跳过会让"没验证"与"验证通过"呈现同一个绿色。

测试残留数据可按标记清理（断言失败或进程被强杀时会用到）：

```bash
uv run --with pymysql python tools/clean_it_leftovers.py          # 只统计
uv run --with pymysql python tools/clean_it_leftovers.py --apply  # 真删
```

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
