-- ============================================================================
-- tm_im M0 建表脚本
-- ============================================================================
-- 本文件由 tools/gen_schema.py 自动生成，请勿手工编辑。
-- 如要修改结构，改生成器后重跑：  python tools/gen_schema.py
--
-- 分片布局：1 库 × 16 表（仅分表，单数据源）
-- 分片键：  conv_id
-- 路由算法：message_${conv_id % 16}
--
-- 生成指纹：d539108587e112b0
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------------
-- 库（如需手工建库可取消注释；权限不足请让 DBA 执行）
-- ---------------------------------------------------------------------------
-- CREATE DATABASE IF NOT EXISTS `tm_im`
--   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `tm_im`;

-- ============================================================================
-- 1. 参与者：人与 Agent 同构
-- ============================================================================
-- 「对等」红线的物理体现：只有一张 actor 表，actor_type 区分人/Agent。
-- 领域层禁止依据 actor_type 分支；它只允许出现在「展示元数据」与「投递适配」。

CREATE TABLE IF NOT EXISTS `actor` (
  `id`            BIGINT       NOT NULL                COMMENT 'Snowflake',
  `actor_type`    TINYINT      NOT NULL                COMMENT '1=HUMAN 2=AGENT',
  `handle`        VARCHAR(64)  NOT NULL                COMMENT '@alice',
  `display_name`  VARCHAR(128) NOT NULL,
  `avatar_url`    VARCHAR(512) NULL,
  `bio`           VARCHAR(512) NULL,
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=ACTIVE 2=SUSPENDED',
  `created_at`    DATETIME(3)  NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_handle` (`handle`),
  KEY `idx_type_status` (`actor_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='唯一参与者表：人/Agent 同构';

-- 凭据隔离到独立表：actor 是高频读取的公开信息，凭据不应与之同页
--
-- uk_secret_hash 不是可选项：api_key 里不含 actor_id（格式固定为
-- sk_live_<随机串>，见 02-auth.md §3.1），服务端只能拿「密钥的哈希」反查
-- 是哪个 Actor。没有这个唯一索引，每次鉴权都会变成 actor_secret 的全表扫描 ——
-- 而 actor_secret 是全库唯一一张「每行都对应一个账号」的表，
-- 长连接场景下它是被查得最频繁的表之一。
--
-- 同一张表承载三类凭据（见 secret_type），因为它们的生命周期、轮换方式与
-- 「谁能读到」完全一致：都是「只有平台自己能读，对外只给一次明文」。
-- 唯一的例外是 WEBHOOK_SECRET：它必须能被**读回明文**才能用于签名
-- （HMAC 的密钥不能是哈希），所以 secret_hash 列里那一行实际存的是明文 ——
-- 这一点写在 SecretType 的注释里，也写在 02-auth.md §3.1。
CREATE TABLE IF NOT EXISTS `actor_secret` (
  `actor_id`      BIGINT       NOT NULL,
  `secret_type`   TINYINT      NOT NULL                COMMENT '1=密码哈希 2=API_KEY哈希 3=WEBHOOK密钥(明文,需用于签名)',
  `secret_hash`   VARCHAR(255) NOT NULL,
  `last_used_at`  DATETIME(3)  NULL,
  PRIMARY KEY (`actor_id`, `secret_type`),
  UNIQUE KEY `uk_secret_hash` (`secret_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='凭据，与人/Agent 无关';

-- Agent 扩展：Human 无此行。owner_actor 防止 Agent 成为孤儿
CREATE TABLE IF NOT EXISTS `agent_profile` (
  `actor_id`      BIGINT       NOT NULL,
  `owner_actor`   BIGINT       NOT NULL                COMMENT '归属人类',
  `endpoint_url`  VARCHAR(512) NULL                    COMMENT 'webhook 回调',
  `push_mode`     TINYINT      NOT NULL                COMMENT '1=WEBHOOK 2=WS 3=PULL',
  `capabilities`  JSON         NULL                    COMMENT '["text","image"]',
  `model_info`    VARCHAR(128) NULL,
  `rate_limit`    INT          NOT NULL DEFAULT 60     COMMENT '每秒配额',
  PRIMARY KEY (`actor_id`),
  KEY `idx_owner` (`owner_actor`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent 专属扩展';

-- ============================================================================
-- 2. 会话与成员
-- ============================================================================

CREATE TABLE IF NOT EXISTS `conversation` (
  `id`           BIGINT       NOT NULL,
  `conv_type`    TINYINT      NOT NULL                COMMENT '1=DIRECT 2=GROUP',
  `title`        VARCHAR(128) NULL,
  `owner_actor`  BIGINT       NULL                    COMMENT '群主；单聊为 NULL',
  `seq_counter`  BIGINT       NOT NULL DEFAULT 0      COMMENT 'Redis 不可用时的兜底序号',
  `pair_key`     VARCHAR(80)  NULL                    COMMENT '单聊去重键：min_max',
  `created_at`   DATETIME(3)  NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pair_key` (`pair_key`),
  KEY `idx_owner` (`owner_actor`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话；单聊/群聊同构';

-- 注意：不做「单聊双方唯一」的额外约束——单聊会话由 pair_key 保证唯一

CREATE TABLE IF NOT EXISTS `conversation_member` (
  `conv_id`        BIGINT      NOT NULL,
  `actor_id`       BIGINT      NOT NULL,
  `role`           TINYINT     NOT NULL DEFAULT 3      COMMENT '1=OWNER 2=ADMIN 3=MEMBER',
  `last_read_seq`  BIGINT      NOT NULL DEFAULT 0      COMMENT '统一未读游标',
  `muted`          TINYINT(1)  NOT NULL DEFAULT 0,
  `joined_at`      DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `actor_id`),
  KEY `idx_actor` (`actor_id`)                         COMMENT '拉「我的会话列表」'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话成员';

-- ============================================================================
-- 3. 消息分片表（逻辑表 message → 物理表 message_0 .. message_15）
-- ============================================================================
-- 分片键 conv_id，路由 message_${conv_id % 16}
--
-- 两条硬约束（见 DESIGN §8.5 / §8.6），改结构时务必保留：
--   1. PRIMARY KEY (conv_id, seq)
--      含分片列 → 同会话消息物理聚簇，范围扫描（拉历史）走顺序 IO
--   2. UNIQUE KEY uk_message_idem (conv_id, sender_id, client_msg_id)
--      含分片列 → 分片表的唯一约束只在单分片内生效，
--      若不含 conv_id，跨会话的重复消息将无法被数据库拦截，幂等静默失效
--
-- 另注：不建外键。ShardingSphere 下跨分片外键无法保证，且分片表写入
--       会显著放大锁范围。参照完整性由应用层保证。

CREATE TABLE IF NOT EXISTS `message_0` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=0';

CREATE TABLE IF NOT EXISTS `message_1` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=1';

CREATE TABLE IF NOT EXISTS `message_2` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=2';

CREATE TABLE IF NOT EXISTS `message_3` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=3';

CREATE TABLE IF NOT EXISTS `message_4` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=4';

CREATE TABLE IF NOT EXISTS `message_5` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=5';

CREATE TABLE IF NOT EXISTS `message_6` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=6';

CREATE TABLE IF NOT EXISTS `message_7` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=7';

CREATE TABLE IF NOT EXISTS `message_8` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=8';

CREATE TABLE IF NOT EXISTS `message_9` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=9';

CREATE TABLE IF NOT EXISTS `message_10` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=10';

CREATE TABLE IF NOT EXISTS `message_11` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=11';

CREATE TABLE IF NOT EXISTS `message_12` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=12';

CREATE TABLE IF NOT EXISTS `message_13` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=13';

CREATE TABLE IF NOT EXISTS `message_14` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=14';

CREATE TABLE IF NOT EXISTS `message_15` (
  `id`            BIGINT      NOT NULL                 COMMENT 'Snowflake',
  `conv_id`       BIGINT      NOT NULL                 COMMENT '★分片键',
  `seq`           BIGINT      NOT NULL                 COMMENT '会话内严格递增',
  `sender_id`     BIGINT      NOT NULL,
  `msg_type`      TINYINT     NOT NULL                 COMMENT '1=TEXT 2=IMAGE 3=SYSTEM',
  `content`       JSON        NOT NULL,
  `reply_to`      BIGINT      NULL,
  `client_msg_id` VARCHAR(64) NULL                     COMMENT '幂等键',
  `created_at`    DATETIME(3) NOT NULL,
  PRIMARY KEY (`conv_id`, `seq`),
  UNIQUE KEY `uk_message_idem` (`conv_id`, `sender_id`, `client_msg_id`),
  KEY `idx_conv_time` (`conv_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息分片表 n=15';

-- ============================================================================
-- 4. 社交与广场
-- ============================================================================

-- 好友关系：约定 actor_a < actor_b，消除方向，避免存两份
-- 这样「是否是好友」只需一次点查，且天然去重
--
-- 本表同时承载「请求」的生命周期（DESIGN §11.4：发起 → friendship(PENDING) → ACCEPTED）。
-- 之所以不另开一张 friend_request 表：「谁是发起人」「当前是什么状态」这两件事
-- 在两个表里各存一份就会不一致，而不一致的表现是「请求被接受了，但发消息仍说不是好友」。
CREATE TABLE IF NOT EXISTS `friendship` (
  `request_id` BIGINT       NOT NULL                  COMMENT '好友请求 id（雪花号）；accept/reject 按它定位',
  `actor_a`    BIGINT       NOT NULL                  COMMENT '约定 actor_a < actor_b',
  `actor_b`    BIGINT       NOT NULL,
  `status`     TINYINT      NOT NULL                  COMMENT '1=PENDING 2=ACCEPTED 3=BLOCKED',
  `initiator`  BIGINT       NOT NULL                  COMMENT '发起方，用于展示「谁加的你」',
  `message`    VARCHAR(255) NULL                      COMMENT '请求附言（仅 PENDING 时有意义）',
  `expires_at` DATETIME(3)  NOT NULL                  COMMENT 'PENDING 的失效时间（ACCEPTED/BLOCKED 后保留原值，不再有意义）',
  `created_at` DATETIME(3)  NOT NULL                  COMMENT '关系（或请求）建立时间',
  `updated_at` DATETIME(3)  NOT NULL                  COMMENT '最后一次状态变更时间；ACCEPTED 行的它就是 friends_since',
  PRIMARY KEY (`actor_a`, `actor_b`),
  UNIQUE KEY `uk_request_id` (`request_id`),
  KEY `idx_b` (`actor_b`, `status`)                  COMMENT '反向查询',
  KEY `idx_initiator_status` (`initiator`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='好友关系（含请求生命周期），无序对存储';

-- 两个冗余计数列（like_count / comment_count）不是「优化」，是**读取侧的必需字段**：
-- 信息流每页 20 条都要带 like_count/comment_count/liked_by_me（03-rest-api.md §6.2），
-- 若每次现算，一页就是 20 次 COUNT(*) + 20 次 EXISTS —— 而信息流是全站读得最频繁的接口。
-- 代价是「计数与明细必须同事务更新」（见 PostLikeRepositoryImpl/PostCommentRepositoryImpl）：
-- 只有同事务才能保证「要么都成功、要么都不发生」，跨事务的修正任务则会长期漂移。
--
-- client_post_id 是幂等键（07-errors-limits.md §3.5 指出「发动态无幂等键 → 重试会重复」）：
-- 它可空（老的调用方不带它），唯一约束含 author_id，因此「同一个人的同一条草稿重复提交」
-- 只会落一行；而 NULL 在 MySQL 的唯一索引里不参与去重，所以「不带幂等键」仍可有任意多条。
CREATE TABLE IF NOT EXISTS `post` (
  `id`             BIGINT      NOT NULL,
  `author_id`      BIGINT      NOT NULL,
  `content`        JSON        NOT NULL,
  `visibility`     TINYINT     NOT NULL DEFAULT 1   COMMENT '1=PUBLIC 2=FRIENDS_ONLY',
  `like_count`     INT         NOT NULL DEFAULT 0   COMMENT '冗余计数，与 post_like 同事务更新',
  `comment_count`  INT         NOT NULL DEFAULT 0   COMMENT '冗余计数，与 post_comment 同事务更新',
  `client_post_id` VARCHAR(64) NULL                 COMMENT '幂等键（客户端生成，可空）',
  `created_at`     DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_idem` (`author_id`, `client_post_id`)  COMMENT '幂等：同一作者的同一 client_post_id 只落一行（NULL 不参与）',
  KEY `idx_author_time` (`author_id`, `created_at`),
  KEY `idx_visibility_time` (`visibility`, `created_at`)  COMMENT '广场全局流'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态';

-- 点赞：主键 (post_id, actor_id) 同时回答两个问题——
--   「这条动态有哪些人赞了」（前缀扫描）与「我赞过它吗」（整键点查），
-- 所以它不需要单独的 liked_by_me 列或第二个索引。
-- 重复点赞由主键冲突直接拦住（应用层翻成 40907），而不是「先查再写」：
-- 后者在并发双击下两个请求都会查不到、然后都插入，计数被加两次。
CREATE TABLE IF NOT EXISTS `post_like` (
  `post_id`    BIGINT      NOT NULL,
  `actor_id`   BIGINT      NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`post_id`, `actor_id`),
  KEY `idx_actor_time` (`actor_id`, `created_at`)      COMMENT '我赞过的（个人页/风控）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态点赞';

-- 评论：content 用 VARCHAR(1000) 而不是 JSON ——
-- 评论是「一段文字 + 一个可选的回复目标」，而 reply_to_comment_id 已经把那半个结构
-- 提成了列；再套一层 JSON 只会让「内容超长」这个校验变成对 JSON 内部字段的校验。
-- 长度上限与动态正文分开：正文 5000 字符（40006），评论 1000 字符。
CREATE TABLE IF NOT EXISTS `post_comment` (
  `id`                  BIGINT        NOT NULL      COMMENT 'Snowflake',
  `post_id`             BIGINT        NOT NULL,
  `author_id`           BIGINT        NOT NULL,
  `content`             VARCHAR(1000) NOT NULL,
  `reply_to_comment_id` BIGINT        NULL          COMMENT '被回复的评论 id；顶层评论为 NULL',
  `created_at`          DATETIME(3)   NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_post_time` (`post_id`, `created_at`)       COMMENT '按动态拉评论'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态评论';

-- 收件箱式时间线（写扩散产物）。
-- 主键含 score → ORDER BY score DESC 走聚簇索引，无需额外排序
CREATE TABLE IF NOT EXISTS `feed_item` (
  `owner_id`  BIGINT NOT NULL                        COMMENT '收件人',
  `score`     BIGINT NOT NULL                        COMMENT '排序分（好友加权后）',
  `post_id`   BIGINT NOT NULL,
  `author_id` BIGINT NOT NULL,
  PRIMARY KEY (`owner_id`, `score`, `post_id`),
  KEY `idx_post` (`post_id`)                         COMMENT '删除动态时清理'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='广场收件箱';

CREATE TABLE IF NOT EXISTS `media` (
  `id`         BIGINT       NOT NULL,
  `owner_id`   BIGINT       NOT NULL,
  `object_key` VARCHAR(512) NOT NULL,
  `mime`       VARCHAR(64)  NOT NULL,
  `width`      INT          NULL,
  `height`     INT          NULL,
  `size_bytes` BIGINT       NULL,
  `created_at` DATETIME(3)  NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_owner_time` (`owner_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='媒体元数据（对象存储只存 key）';

-- ============================================================================
-- 共 16 张 message 分片表 + 11 张非分片表
-- ============================================================================
