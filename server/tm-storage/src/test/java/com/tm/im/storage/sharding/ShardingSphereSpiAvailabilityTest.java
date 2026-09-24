package com.tm.im.storage.sharding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShardingSphere 的 SPI 提供者在 classpath 上是否齐全。
 *
 * <p><b>为什么需要这个测试</b>：5.5.3 的 {@code shardingsphere-jdbc} 只是个门面，
 * 分片、单机模式、连接池元数据、SQL 方言解析器、URL 加载器全部要显式声明依赖。
 * 漏掉任何一个，报错都<b>不指向缺依赖</b>：
 *
 * <pre>
 *   漏 sharding-core             -&gt; Invalid tag: !SHARDING           （像 YAML 写错）
 *   漏 pool-hikari               -&gt; NullPointerException @ StorageUnit（像框架 bug）
 *   漏 standalone-mode-core      -&gt; SPI-00001: No implementation class
 *                                   ... 'ContextManagerBuilder' with type 'null'（没说哪种 mode）
 *   漏 parser-sql-engine-mysql   -&gt; 找不到 SQLParserEngine type=MySQL（没提"方言"）
 * </pre>
 *
 * 这四个症状我逐个踩过，累计排查了好几轮。它们本质上是同一件事：
 * <b>某个 SPI 接口在 classpath 上没有任何实现</b>。
 * 那就直接断言这件事，而不是等运行期以变形的方式暴露出来。
 *
 * <p><b>为什么不写集成测试</b>：这件事与数据库毫无关系，只需要 classpath。
 * 放进默认的 {@code mvn test}（离线可跑、不需要凭据）才能保证它每次都被执行。
 *
 * <p><b>为什么直接读 {@code META-INF/services} 而不调 ShardingSphere 的加载器</b>：
 * 读文件不实例化任何 SPI 实现，没有副作用，也没有"某个实现需要 init 参数才能构造"
 * 之类的偶发失败。要断言的本来就是"这个 jar 在不在 classpath 上"。
 *
 * <p><b>为什么 INLINE 那段要用反射</b>：本类的职责是"在依赖缺失时给出可读的诊断"。
 * 若编译期就引用 {@code ShardingAlgorithm} / {@code TypedSPILoader}，
 * 一旦 sharding-core 被移除，本类会<b>编译失败</b>——那正是最需要它说话的时刻。
 * 所以这里没有任何 ShardingSphere 的编译期依赖，缺依赖时本类仍能编译并列出清单。
 */
class ShardingSphereSpiAvailabilityTest {

    /**
     * 每行 = SPI 接口、必须存在的实现类、以及"缺了它会怎样"。
     *
     * <p>实现类用全限定名精确断言，而不是只数个数：只数个数的话，
     * 误引入另一个提供者会把缺口掩盖掉。
     */
    private static final String[][] REQUIRED = {
            {
                    "org.apache.shardingsphere.infra.yaml.config.swapper.rule.YamlRuleConfigurationSwapper",
                    "org.apache.shardingsphere.sharding.yaml.swapper.YamlShardingRuleConfigurationSwapper",
                    "shardingsphere-sharding-core 未引入：配置里的 !SHARDING 标签无法识别，"
                            + "报 \"Invalid tag: !SHARDING\"（错误信息指向 YAML，实为缺依赖）",
            },
            {
                    "org.apache.shardingsphere.infra.route.SQLRouter",
                    "org.apache.shardingsphere.sharding.route.engine.ShardingSQLRouter",
                    "shardingsphere-sharding-core 未引入：没有任何路由引擎，"
                            + "逻辑表 message 不会被打散到 message_0..15",
            },
            {
                    "org.apache.shardingsphere.sharding.spi.ShardingAlgorithm",
                    "org.apache.shardingsphere.sharding.algorithm.sharding.inline.InlineShardingAlgorithm",
                    "shardingsphere-sharding-core 未引入：sharding.yaml 用的 type: INLINE 无实现，"
                            + "message_inline 算法无法实例化",
            },
            {
                    "org.apache.shardingsphere.infra.datasource.pool.metadata.DataSourcePoolMetaData",
                    "org.apache.shardingsphere.infra.datasource.pool.hikari.metadata.HikariDataSourcePoolMetaData",
                    "shardingsphere-infra-data-source-pool-hikari 未引入：Hikari 的 jdbcUrl -> url "
                            + "同义词映射缺失，StorageUnit 里 standardProps.get(\"url\") 为 null 并抛 NPE",
            },
            {
                    "org.apache.shardingsphere.mode.manager.builder.ContextManagerBuilder",
                    "org.apache.shardingsphere.mode.manager.standalone.StandaloneContextManagerBuilder",
                    "shardingsphere-standalone-mode-core 未引入（由 "
                            + "shardingsphere-standalone-mode-repository-memory 传递带入）："
                            + "报 SPI-00001 No implementation class ... ContextManagerBuilder with type 'null'",
            },
            {
                    "org.apache.shardingsphere.mode.repository.standalone.StandalonePersistRepository",
                    "org.apache.shardingsphere.mode.repository.standalone.memory.MemoryRepository",
                    "shardingsphere-standalone-mode-repository-memory 未引入：单机模式没有元数据仓库",
            },
            {
                    "org.apache.shardingsphere.infra.url.spi.ShardingSphereLocalFileURLLoader",
                    "org.apache.shardingsphere.infra.url.absolutepath.AbsolutePathLocalFileURLLoader",
                    "shardingsphere-infra-url-absolutepath 未引入：生产用的 "
                            + "jdbc:shardingsphere:absolutepath:/etc/tm/sharding.yaml 无法加载",
            },
            {
                    "org.apache.shardingsphere.infra.url.spi.ShardingSphereLocalFileURLLoader",
                    "org.apache.shardingsphere.infra.url.classpath.ClassPathLocalFileURLLoader",
                    "shardingsphere-infra-url-classpath 未引入：开发用的 "
                            + "jdbc:shardingsphere:classpath:sharding.yaml 无法加载。"
                            + "注意 infra-url-core 只含接口，两个加载器都不自带",
            },
            {
                    "org.apache.shardingsphere.sql.parser.spi.DialectSQLParserFacade",
                    "org.apache.shardingsphere.sql.parser.engine.mysql.parser.MySQLParserFacade",
                    "shardingsphere-parser-sql-engine-mysql 未引入：MySQL 方言的 SQL 解析器缺失",
            },
            {
                    "org.apache.shardingsphere.authority.spi.PrivilegeProvider",
                    "org.apache.shardingsphere.authority.provider.simple.AllPermittedPrivilegeProvider",
                    "shardingsphere-authority-simple 未引入：权限 SPI 无实现",
            },
    };

    @Test
    @DisplayName("ShardingSphere 运行所需的 SPI 实现必须都在 classpath 上")
    void requiredSpiProvidersArePresent() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String[] row : REQUIRED) {
            String spi = row[0];
            String required = row[1];
            String why = row[2];
            String providers = readProviders(spi);
            if (!providers.contains(required)) {
                missing.add(String.format(
                        "%n  SPI  : %s%n  缺少 : %s%n  症状 : %s%n  实际 : %s",
                        spi, required, why,
                        providers.isEmpty() ? "(classpath 上该 SPI 没有任何提供者)" : providers.trim()));
            }
        }
        assertThat(missing)
                .as("以下 SPI 提供者不在 classpath 上——请对照 server/tm-storage/pom.xml 补齐依赖")
                .isEmpty();
    }

    @Test
    @DisplayName("JDBC 驱动自身已注册为 java.sql.Driver")
    void shardingSphereDriverIsRegistered() throws IOException {
        // 少了这条，DriverManager 会抛 "No suitable driver"，
        // 而那通常被误读成"连接串写错了"。
        assertThat(readProviders("java.sql.Driver"))
                .as("ShardingSphereDriver 必须由 jar 内的 META-INF/services 注册")
                .contains("org.apache.shardingsphere.driver.ShardingSphereDriver");

        // 注：AssertJ 3.27 已移除 IteratorAssert.anyMatch，所以先收集成 List 再断言
        List<String> registered = new ArrayList<>();
        DriverManager.getDrivers().asIterator()
                .forEachRemaining(d -> registered.add(d.getClass().getName()));
        assertThat(registered)
                .as("DriverManager 应已能发现 ShardingSphere 驱动")
                .anyMatch(name -> name.startsWith("org.apache.shardingsphere"));
    }

    @Test
    @DisplayName("模板里 INLINE 算法的表达式能被真实解析，且取模数与 actualDataNodes 一致")
    void inlineAlgorithmInTemplateIsValid() throws Exception {
        Path template = findRepoRoot().resolve("deploy/conf/sharding.yaml.example");
        String text = Files.readString(template, StandardCharsets.UTF_8);

        String expression = firstGroup(text, "^\\s*algorithm-expression:\\s*(.+?)\\s*$");
        int tables = Integer.parseInt(
                firstGroup(text, "actualDataNodes:\\s*ds_0\\.message_\\$\\{0\\.\\.(\\d+)\\}"));
        int modulo = Integer.parseInt(firstGroup(expression, "%\\s*(\\d+)"));

        // 分片数在两个地方各写了一遍：actualDataNodes 的 ${0..15} 与表达式里的 % 16。
        // 只改一处是极难发现的 bug：路由会把行写进 message_16 这种没被声明过的物理表，
        // 或者有两个分片永远收不到数据——两种都不报错。
        assertThat(modulo)
                .as("表达式的取模数 %d 必须等于 actualDataNodes 声明的表数 %d", modulo, tables + 1)
                .isEqualTo(tables + 1);

        // 拿模板里那份表达式去初始化真实的算法实现：表达式写错、用了不支持的运算符，
        // 这里就会抛出来，且不需要连库。
        // （集成测试验证的是"算出来的表对不对"，不验证"表达式本身能不能用"。）
        Properties props = new Properties();
        props.setProperty("algorithm-expression", expression);
        Object algorithm = typedSpiLoaderLookup("org.apache.shardingsphere.sharding.spi.ShardingAlgorithm",
                "INLINE", props);
        assertThat(algorithm.getClass().getSimpleName())
                .as("type: INLINE 应解析到内联分片算法")
                .isEqualTo("InlineShardingAlgorithm");
    }

    /**
     * 反射调用 {@code TypedSPILoader.getService(Class, Object, Properties)}。
     *
     * <p>用反射的代价是签名写错要到运行期才发现，所以这里显式检查：
     * 类本身找不到（缺依赖）与调用失败（签名不符）分开报，避免前者被后者的异常掩盖。
     */
    private static Object typedSpiLoaderLookup(final String spiInterface, final String type,
                                               final Properties props) {
        final String loader = "org.apache.shardingsphere.infra.spi.type.typed.TypedSPILoader";
        final Class<?> spi;
        final Class<?> loaderClass;
        try {
            spi = Class.forName(spiInterface);
            loaderClass = Class.forName(loader);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("classpath 上找不到 " + e.getMessage()
                    + "——对应的 ShardingSphere 模块没有引入", e);
        }
        try {
            Method getService = loaderClass.getMethod("getService", Class.class, Object.class, Properties.class);
            return getService.invoke(null, spi, type, props);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("TypedSPILoader 上没有 getService(Class, Object, Properties)，"
                    + "ShardingSphere 版本可能变了，需要更新本测试", e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("解析 type=" + type + " 失败: " + e.getCause(), e.getCause());
        } catch (IllegalAccessException e) {
            throw new AssertionError("无法反射调用 TypedSPILoader.getService（可见性变了）", e);
        }
    }

    /** 从当前工作目录向上找到仓库根（以 deploy/ 与 server/ 为标志）。 */
    private static Path findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("deploy")) && Files.isDirectory(dir.resolve("server"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("从 " + Paths.get("").toAbsolutePath()
                + " 向上找不到仓库根（应同时包含 deploy/ 与 server/）");
    }

    private static String firstGroup(final String text, final String regex) {
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(text);
        if (!m.find()) {
            throw new IllegalStateException("模板里匹配不到 " + regex);
        }
        return m.group(1);
    }

    /** 汇总 classpath 上某个 SPI 的所有提供者（每行一个全限定类名）。 */
    private static String readProviders(final String spi) throws IOException {
        StringBuilder result = new StringBuilder();
        Enumeration<URL> resources = ShardingSphereSpiAvailabilityTest.class.getClassLoader()
                .getResources("META-INF/services/" + spi);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                reader.lines()
                        .map(String::trim)
                        // 服务文件里允许注释，ShardingSphere 自己那些文件每个都带一段
                        // Apache 许可证头，不滤掉会把"存在但不是实现"的行也算进来
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(line -> result.append(line).append('\n'));
            }
        }
        return result.toString();
    }
}
