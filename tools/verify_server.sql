-- ============================================================================
-- tm_im 环境核验脚本（在数据库服务器上执行）
-- ============================================================================
--
-- 用途：一次性确认 MySQL 服务端的真实版本、排序规则、账号权限，
--      用于排除「客户端版本」与「服务端版本」混淆导致的误判。
--
-- 执行方式（在服务器本机，或通过 mysql 客户端）：
--     mysql -h 127.0.0.1 -u root -p < tools/verify_server_version.sql
--
-- 为什么需要它：
--   从外部只能读到握手包，能确认版本但读不到账号与权限（因为登录失败）。
--   本脚本在服务端侧给出完整事实，避免来回猜测。
--
-- 常见误判来源：
--   `mysql --version` 输出的是**客户端**版本，与你连的服务端无关。
--   客户端 8.0 连接 5.7 服务端是完全正常的组合。
--   判断服务端版本必须用 SELECT VERSION() 或 SHOW VARIABLES。
-- ============================================================================

SELECT '=== 1. 服务端真实版本（这才是服务端） ===' AS section;

-- 注意：这里的 VERSION() 是**服务端**版本，不是客户端版本
SELECT
    VERSION()                                   AS server_version,
    @@version_comment                           AS version_comment,
    @@version_compile_os                        AS compile_os,
    @@version_compile_machine                   AS compile_machine;

-- 服务端大版本速判
SELECT
    SUBSTRING_INDEX(VERSION(), '.', 1)          AS major_version,
    CASE SUBSTRING_INDEX(VERSION(), '.', 1)
        WHEN '5' THEN 'MySQL 5.x（5.7 已停止安全支持）'
        WHEN '8' THEN 'MySQL 8.x'
        ELSE '其他/衍生版本（MariaDB/Percona？）'
    END                                         AS version_family;


SELECT '=== 2. 字符集与排序规则（决定建表兼容性） ===' AS section;

SELECT
    @@character_set_server                      AS charset_server,
    @@collation_server                          AS collation_server;

-- utf8mb4 的默认排序规则会随大版本变化：
--   MySQL 5.7 → utf8mb4_general_ci
--   MySQL 8.0 → utf8mb4_0900_ai_ci
-- 注意：CHARACTER_SETS 表的列是 DEFAULT_COLLATE_NAME，没有 IS_DEFAULT 列
SELECT
    CHARACTER_SET_NAME,
    DEFAULT_COLLATE_NAME                        AS utf8mb4_default_collation,
    MAXLEN
FROM information_schema.CHARACTER_SETS
WHERE CHARACTER_SET_NAME = 'utf8mb4';

-- 8.0 专有排序规则是否存在（在 5.7 上此查询返回空）
SELECT COUNT(*) AS has_8_0_only_collations
FROM information_schema.COLLATIONS
WHERE COLLATION_NAME LIKE 'utf8mb4\_0900%';


SELECT '=== 3. 大小写敏感性与时区 ===' AS section;

SELECT
    @@lower_case_table_names                    AS lower_case_table_names,
    @@time_zone                                 AS session_tz,
    @@system_time_zone                          AS system_tz,
    @@transaction_isolation                     AS isolation_level,
    @@default_storage_engine                    AS storage_engine,
    @@sql_mode                                  AS sql_mode,
    @@max_connections                           AS max_connections;

-- lower_case_table_names: 0=敏感(Linux 默认) 1=不敏感(Windows 默认)
-- 跨平台迁移时此值不同会导致表名找不到


SELECT '=== 4. tm_im 账号是否存在 / 允许来源 / 认证插件 ===' AS section;

SELECT
    user,
    host,
    plugin                                      AS auth_plugin,
    account_locked,
    password_expired
FROM mysql.user
WHERE user = 'tm_im';

-- 若上面为空 → 账号不存在，需要 CREATE USER
-- plugin 为 caching_sha2_password 时，部分老客户端需额外配置


SELECT '=== 5. tm_im 账号的权限 ===' AS section;

-- 先列出所有 host 形式的同名账号。
-- 注意：此处刻意**不**用 CONCAT 拼出 "SHOW GRANTS FOR ..." 命令字符串。
-- 那种写法要嵌套转义引号（'''），一旦本脚本通过 shell / docker exec -e
-- 传参就会因引号层叠而碎裂，属于典型的"本地能跑、现场报错"。
-- 请把下面的 user/host 组合直接填进 SHOW GRANTS 手工执行。
SELECT
    user,
    host,
    plugin,
    account_locked
FROM mysql.user
WHERE user = 'tm_im';

-- 账号存在时，把上面的 host 填进来执行（最常见是 localhost 与 %）：
--   SHOW GRANTS FOR 'tm_im'@'localhost';
--   SHOW GRANTS FOR 'tm_im'@'%';
--
-- 若上面查询为空 → 账号不存在，需要创建：
--   CREATE USER 'tm_im'@'%' IDENTIFIED BY '<密码>';
--   GRANT ALL PRIVILEGES ON `tm_im`.* TO 'tm_im'@'%';
--   FLUSH PRIVILEGES;


SELECT '=== 6. 库是否存在及其默认排序规则 ===' AS section;

SELECT
    SCHEMA_NAME,
    DEFAULT_CHARACTER_SET_NAME                  AS charset,
    DEFAULT_COLLATION_NAME                      AS collation
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME = 'tm_im';

-- 若库已存在且排序规则与建表脚本不一致，跨表 JOIN 会报
-- Illegal mix of collations。建表脚本使用 utf8mb4_0900_ai_ci
-- （MySQL 8.0+ 专有；当前服务端 8.4.4）。
-- 旧 5.7 实例上应改用 --collation utf8mb4_unicode_ci 重新生成。


SELECT '=== 7. 库内现有表 ===' AS section;

SELECT
    TABLE_NAME,
    ENGINE,
    TABLE_COLLATION                            AS collation,
    TABLE_ROWS                                 AS approx_rows
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'tm_im'
ORDER BY TABLE_NAME;

-- 预期为空（尚未建表）；若已有 message_N 表，说明之前建过


SELECT '=== 8. 服务端实际监听的地址与端口 ===' AS section;

SHOW VARIABLES LIKE 'bind_address';
SHOW VARIABLES LIKE 'port';
SHOW VARIABLES LIKE 'skip_networking';
SHOW VARIABLES LIKE 'socket';

-- bind_address 为 127.0.0.1 时只能本机连接，外部访问会失败
-- （这与 redis 的 bind 配置是同一类问题）


SELECT '=== 9. InnoDB 可用性（事务与行锁依赖） ===' AS section;

SELECT ENGINE, SUPPORT, COMMENT
FROM information_schema.ENGINES
WHERE ENGINE = 'InnoDB';

SHOW VARIABLES LIKE 'innodb_version';
SHOW VARIABLES LIKE 'innodb_file_per_table';
