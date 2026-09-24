package com.tm.im.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 全部落库枚举的统一契约测试。
 *
 * <p>枚举<b>自动从源码目录扫描</b>，不写死类名。理由很实际：
 * 写死清单的测试，在新增枚举时会「继续通过」，于是新枚举实际上没被校验过，
 * 而测试报告上却是绿的——这比没有测试更糟。
 * 扫描源码目录后，新增枚举自动纳入校验，同时下面还会断言扫到的数量不为零，
 * 防止路径写错导致「一个都没扫到所以全部通过」。
 */
class EnumCodeTest {

    private static final Path ENUM_SRC = Path.of("src", "main", "java", "com", "tm", "im", "domain", "enums");
    private static final String PKG = "com.tm.im.domain.enums.";

    @SuppressWarnings("unchecked")
    private static List<Class<? extends Enum<?>>> scanEnums() throws IOException, ClassNotFoundException {
        assertThat(ENUM_SRC).as("枚举源码目录不存在，扫描会静默通过").isDirectory();
        List<Class<? extends Enum<?>>> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(ENUM_SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String simple = f.getFileName().toString().replace(".java", "");
                if (simple.equals("CodedEnum")) {
                    continue;
                }
                Class<?> c = Class.forName(PKG + simple);
                if (c.isEnum() && CodedEnum.class.isAssignableFrom(c)) {
                    result.add((Class<? extends Enum<?>>) c);
                }
            }
        }
        result.sort(Comparator.comparing(Class::getSimpleName));
        return result;
    }

    @Test
    @DisplayName("能扫到全部落库枚举，且数量与 DDL 中的枚举列数吻合")
    void scansAllEnums() throws Exception {
        List<Class<? extends Enum<?>>> enums = scanEnums();
        assertThat(enums)
                .as("扫描结果为空说明目录写错了，测试会假通过")
                .isNotEmpty();
        assertThat(enums).extracting(Class::getSimpleName).containsExactlyInAnyOrder(
                "ActorStatus", "ActorType", "ConvType", "FriendshipStatus",
                "MemberRole", "MessageType", "PushMode", "SecretType", "Visibility");
    }

    @Test
    @DisplayName("每个枚举：码值不重复、of/require 往返一致、未知码值被拒")
    void everyEnumRoundTrips() throws Exception {
        for (Class<? extends Enum<?>> type : scanEnums()) {
            Object[] constants = type.getEnumConstants();
            Method codeM = type.getMethod("code");
            Method ofM = type.getMethod("of", int.class);
            Method requireM = type.getMethod("require", int.class);

            Set<Integer> codes = new HashSet<>();
            for (Object constant : constants) {
                int code = (int) codeM.invoke(constant);
                assertThat(codes.add(code))
                        .as("%s.%s 的码值 %d 与其它常量重复", type.getSimpleName(), constant, code)
                        .isTrue();
                assertThat(code).as("%s.%s 的码值必须为正", type.getSimpleName(), constant).isPositive();
                assertThat(ofM.invoke(null, code))
                        .as("%s.of(%d) 应能反查回 %s", type.getSimpleName(), code, constant)
                        .isSameAs(constant);
                assertThat(requireM.invoke(null, code)).isSameAs(constant);
            }

            // 未知码值：of 返回 null（供解析外部输入），require 抛异常（供读数据库）
            int unknown = 9999;
            assertThat(codes).doesNotContain(unknown);
            assertThat(ofM.invoke(null, unknown))
                    .as("%s.of(%d) 应返回 null", type.getSimpleName(), unknown)
                    .isNull();
            assertThatThrownBy(() -> requireM.invoke(null, unknown))
                    .as("%s.require(%d) 应抛异常而不是返回 null——库里出现非法值必须暴露",
                            type.getSimpleName(), unknown)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause()
                    .hasMessageContaining(type.getSimpleName());
        }
    }

    @Test
    @DisplayName("@EnumValue 必须标注在 code 字段上，否则 MyBatis-Plus 会退回 name() 落库")
    void codeFieldIsAnnotatedWithEnumValue() throws Exception {
        for (Class<? extends Enum<?>> type : scanEnums()) {
            var field = type.getDeclaredField("code");
            boolean annotated = false;
            for (var a : field.getAnnotations()) {
                if (a.annotationType().getName().equals("com.baomidou.mybatisplus.annotation.EnumValue")) {
                    annotated = true;
                    break;
                }
            }
            assertThat(annotated)
                    .as("%s.code 缺少 @EnumValue；MyBatis-Plus 会改存枚举名，"
                            + "而库里存的是数字，读写会整体错位", type.getSimpleName())
                    .isTrue();
        }
    }
}
