package com.tm.im.core.media;

import com.tm.im.domain.media.MediaStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存对象存储替身。
 *
 * <p><b>它还记录「写入顺序」与「被删掉的 key」</b>，而不只是一个 Map：
 * {@code MediaService} 里有两处顺序敏感的逻辑（字节先于元数据、缩略图先于落库后补偿），
 * 而顺序用「最终状态」断言不出来——最终状态在正确与错误实现下都一样。
 * 因此这里保留 {@link #writeOrder()}（含被删的 key），测试才钉得住顺序。
 */
public class InMemoryMediaStore implements MediaStore {

    /** 当前存在的对象。用 LinkedHashMap 让遍历顺序稳定，便于断言。 */
    private final Map<String, byte[]> objects = new LinkedHashMap<>();

    /** 完整操作日志（含已删除的），按发生顺序。 */
    private final List<String> log = new ArrayList<>();

    @Override
    public void put(String key, byte[] content) {
        log.add("put:" + key);
        objects.put(key, content.clone());
    }

    @Override
    public Optional<byte[]> get(String key) {
        log.add("get:" + key);
        byte[] value = objects.get(key);
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    @Override
    public void delete(String key) {
        log.add("delete:" + key);
        objects.remove(key);
    }

    public boolean has(String key) {
        return objects.containsKey(key);
    }

    public byte[] bytes(String key) {
        byte[] value = objects.get(key);
        return value == null ? null : value.clone();
    }

    public int size() {
        return objects.size();
    }

    public List<String> writeOrder() {
        return List.copyOf(log);
    }

    /** 清空日志但保留对象：用于「先把上传做完，再只看后续动作」的断言。 */
    public void clearLog() {
        log.clear();
    }

    /** 手工塞入一个对象（模拟「库里没有、盘上有」或迁移遗留）。 */
    public void seed(String key, byte[] content) {
        objects.put(key, content.clone());
    }

    public Map<String, byte[]> snapshot() {
        return new HashMap<>(objects);
    }
}
