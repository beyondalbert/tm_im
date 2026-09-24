package com.tm.im.common.json;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JSON 门面测试。
 *
 * <p>这里断言的都是<b>会外泄成协议行为</b>的配置，不是随便挑的：
 * 不输出 null 影响消息体大小；时间格式影响客户端解析；
 * 忽略未知字段决定协议能否向前兼容。任何一条被改动，客户端都会先出问题。
 */
class JsonTest {

    record ImageContent(String url, Integer width, Integer height) {
    }

    record TextContent(String text, ImageContent image) {
    }

    record Timed(String name, LocalDateTime at) {
    }

    @Test
    @DisplayName("null 字段不输出——消息体不该被一堆 null 撑大")
    void nullFieldsAreOmitted() {
        assertThat(Json.write(new TextContent("hi", null)))
                .as("image 为 null 时不应出现该键")
                .isEqualTo("{\"text\":\"hi\"}");
    }

    @Test
    @DisplayName("时间序列化为 ISO-8601 字符串，不是时间戳数字数组")
    void timeIsIsoString() {
        String json = Json.write(new Timed("m", LocalDateTime.of(2026, 1, 1, 8, 0, 0, 123_000_000)));
        assertThat(json)
                .as("默认配置会输出 [2026,1,1,8,0,0,123000000] 这种数组，客户端无法直接解析")
                .contains("\"2026-01-01T08:00:00.123\"");
    }

    @Test
    @DisplayName("反序列化忽略未知字段——服务端加字段不该让老客户端崩掉")
    void unknownFieldsAreIgnored() throws Exception {
        TextContent content = Json.read("{\"text\":\"hi\",\"future_field\":42}", TextContent.class);
        assertThat(content.text()).isEqualTo("hi");
    }

    @Test
    @DisplayName("往返一致：对象 → JSON → 对象")
    void roundTrip() {
        TextContent original = new TextContent("看图", new ImageContent("/v1/media/1", 800, 600));
        assertThat(Json.read(Json.write(original), TextContent.class)).isEqualTo(original);
    }

    @Test
    @DisplayName("泛型集合可反序列化")
    void collections() {
        String json = "[{\"text\":\"a\"},{\"text\":\"b\"}]";
        List<TextContent> list = Json.readList(json, TextContent.class);
        assertThat(list).hasSize(2);
        assertThat(list.get(1).text()).isEqualTo("b");

        Map<String, Integer> map = Json.read("{\"x\":1,\"y\":2}", new TypeReference<>() {
        });
        assertThat(map).containsEntry("x", 1).containsEntry("y", 2);
    }

    @Test
    @DisplayName("输出稳定可复现——同样的输入必须产出逐字节相同的 JSON")
    void outputIsDeterministic() {
        TextContent content = new TextContent("hello", null);
        String first = Json.write(content);
        for (int i = 0; i < 100; i++) {
            assertThat(Json.write(content))
                    .as("字段顺序若随机，客户端按原始字节验签会随机失败")
                    .isEqualTo(first);
        }
    }

    @Test
    @DisplayName("序列化失败抛 IllegalArgumentException 且带上类型信息，不返回半截 JSON")
    void serializationFailureIsLoud() {
        assertThatThrownBy(() -> Json.write(new Object() {
            @SuppressWarnings("unused")
            public String getBroken() {
                throw new IllegalStateException("boom");
            }
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JSON 序列化失败");
    }

    @Test
    @DisplayName("反序列化失败给出原始 Jackson 原因，便于定位字段")
    void deserializationFailureIsLoud() {
        assertThatThrownBy(() -> Json.read("{not json", TextContent.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 反序列化失败")
                .hasMessageContaining("TextContent");
    }
}
