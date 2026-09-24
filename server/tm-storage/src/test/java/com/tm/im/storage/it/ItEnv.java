package com.tm.im.storage.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 集成测试的外部服务坐标（真实 MySQL / Redis）。
 *
 * <p><b>为什么读的是一份扁平 properties，而不是 application.yml</b>：
 * 那份 yml 里的 Redis 地址本来就写成 {@code ${TM_REDIS_HOST}} / {@code ${TM_REDIS_PASSWORD}}，
 * 是有意留给部署环境变量的。Java 测试若要读懂它，就得实现一遍占位符解析、
 * 还要处理 {@code ${VAR:default}} 的默认值语法——那是把 Spring 的配置层重写一遍，
 * 写错了会以「连不上」的形式暴露，看起来像服务故障。
 * 所以生成器多输出一份无歧义的坐标（{@code deploy/conf/runtime/it.properties}），
 * 测试只做「按 = 切分」这一件确定的事。
 *
 * <p><b>为什么缺配置要抛异常而不是跳过</b>：跳过会让「没验证」和「验证通过」
 * 呈现同一个绿色。这里与 {@code MessageShardingRoutingIT} 保持同一口径。
 *
 * <pre>
 *   uv run python tools/gen_runtime_config.py
 *   mvn -pl tm-storage -am test -Pit
 * </pre>
 */
public final class ItEnv {

    /** 与 {@code tools/gen_runtime_config.py} 的输出保持一致的约定路径（相对仓库根）。 */
    private static final String DEFAULT_RELATIVE = "deploy/conf/runtime/it.properties";

    private static final Map<String, String> VALUES = load();

    private ItEnv() {
    }

    public static String get(String key) {
        String v = VALUES.get(key);
        if (v == null) {
            throw new IllegalStateException(
                    "it.properties 里缺少键 `" + key + "` —— 生成器与测试的约定不一致了。\n"
                            + "  重新生成： uv run python tools/gen_runtime_config.py");
        }
        return v;
    }

    /** 由 password 之类的敏感项使用：空串是合法值（表示无口令）。 */
    public static String getOrEmpty(String key) {
        return VALUES.getOrDefault(key, "");
    }

    public static int getInt(String key) {
        return Integer.parseInt(get(key).trim());
    }

    /** sharding.yaml 的绝对路径，供 ShardingSphere 驱动使用。 */
    public static String shardingConfig() {
        return get("sharding.yaml");
    }

    /** 走 ShardingSphere 的 jdbc url —— 与生产配置同构（见 application-external.yml.example）。 */
    public static String shardingJdbcUrl() {
        return "jdbc:shardingsphere:absolutepath:" + shardingConfig();
    }

    private static Map<String, String> load() {
        Path file = resolveFile();
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalStateException(
                            "it.properties 第 " + (out.size() + 1) + " 行不是键值对: " + line);
                }
                // 值不做 strip：口令可能以空格结尾，那是真实口令的一部分。
                out.put(line.substring(0, eq).strip(), line.substring(eq + 1));
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 it.properties 失败: " + file, e);
        }
        return out;
    }

    private static Path resolveFile() {
        String prop = System.getProperty("tm.it.properties", "").trim();
        String relative = prop.isEmpty() ? DEFAULT_RELATIVE : prop;
        Path p = Paths.get(relative);
        if (!p.isAbsolute()) {
            // 刻意不写「当前目录的父目录就是仓库根」：surefire 的工作目录是模块目录
            // （server/tm-storage），父目录是 server/ 而不是仓库根——
            // 那个写法只在某些调用方式下碰巧成立，换一种就静默指向不存在的路径。
            // 改为向上查找：哪一层能找到这个相对路径，哪一层就是仓库根。
            p = findUpwards(relative);
        }
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException(
                    "找不到集成测试配置: " + p + "\n"
                            + "  生成： uv run python tools/gen_runtime_config.py\n"
                            + "  运行： mvn -pl tm-storage -am test -Pit");
        }
        return p;
    }

    /** 从当前目录逐层向上找 {@code relative}，最多 6 层。 */
    private static Path findUpwards(String relative) {
        Path start = Paths.get("").toAbsolutePath();
        for (Path dir = start; dir != null && dir.getNameCount() > 0; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return start.resolve(relative);
    }
}
