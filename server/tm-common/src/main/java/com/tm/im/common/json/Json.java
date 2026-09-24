package com.tm.im.common.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.List;

/**
 * JSON 门面：全平台共用一个配置好的 {@link ObjectMapper}。
 *
 * <p>为什么要统一实例而不是各处 {@code new ObjectMapper()}：
 * <ul>
 *   <li>Jackson 的 {@code ObjectMapper} 是<b>线程安全且设计为可共享</b>的，
 *       每次 new 都要重建序列化器缓存，在高频路径上是纯浪费；</li>
 *   <li>更关键的是<b>配置漂移</b>：消息内容是 JSON，客户端按原始字节做签名校验，
 *       如果两个模块用了不同的序列化配置（比如一个写 null、一个不写），
 *       同一条消息在不同链路上会产出不同字节，验签随机失败。</li>
 * </ul>
 *
 * <p>配置取舍：
 * <ul>
 *   <li>{@code NON_NULL}：不输出 null 字段。消息 content 里大量可选字段
 *       （reply_to、图片尺寸），全输出 null 会让消息体无谓膨胀。</li>
 *   <li>时间用 ISO-8601 字符串而非时间戳数字：对外接口可读性优先，
 *       且与接入文档里的示例一致。内部排序一律用 {@code seq}，不依赖时间。</li>
 *   <li>反序列化忽略未知字段：协议要向前兼容——服务端加了新字段之后，
 *       老客户端不应该直接崩。</li>
 * </ul>
 */
public final class Json {

    private static final ObjectMapper MAPPER = buildMapper();

    private Json() {
    }

    private static ObjectMapper buildMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "JSON 序列化失败: " + (value == null ? "null" : value.getClass().getName()), e);
        }
    }

    public static String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "JSON 序列化失败: " + (value == null ? "null" : value.getClass().getName()), e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化失败 → " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    public static <T> T read(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化失败 → " + type.getType() + ": " + e.getOriginalMessage(), e);
        }
    }

    public static <T> List<T> readList(String json, Class<T> elementType) {
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化失败 → List<" + elementType.getSimpleName() + ">", e);
        }
    }
}
