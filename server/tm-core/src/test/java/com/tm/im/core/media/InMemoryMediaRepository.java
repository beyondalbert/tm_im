package com.tm.im.core.media;

import com.tm.im.domain.entity.Media;
import com.tm.im.domain.repository.MediaRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 内存媒体元数据仓储替身。
 *
 * <p>刻意复刻真实实现的两个行为，否则测试会在错误的前提下变绿：
 * <ul>
 *   <li>{@link #insert} 返回传入的对象（真实实现把 id 由 caller 生成并原样返回）；</li>
 *   <li>可以配成「插入必失败」，用于验证上传失败时的字节回收
 *       （真实里那条路径只有主键冲突或库故障才会走到）。</li>
 * </ul>
 */
public class InMemoryMediaRepository implements MediaRepository {

    private final Map<Long, Media> rows = new LinkedHashMap<>();

    /** 置为 true 后 {@link #insert} 抛异常，用于测补偿路径。 */
    private boolean failNextInsert;

    @Override
    public Media insert(Media media) {
        if (failNextInsert) {
            failNextInsert = false;
            throw new org.springframework.dao.DuplicateKeyException("替身：模拟落库失败");
        }
        if (rows.containsKey(media.getId())) {
            throw new org.springframework.dao.DuplicateKeyException("替身：主键重复 id=" + media.getId());
        }
        rows.put(media.getId(), media);
        return media;
    }

    @Override
    public Optional<Media> findById(long mediaId) {
        return Optional.ofNullable(rows.get(mediaId));
    }

    @Override
    public void delete(long mediaId) {
        rows.remove(mediaId);
    }

    public void failNextInsert() {
        this.failNextInsert = true;
    }

    public int size() {
        return rows.size();
    }
}
