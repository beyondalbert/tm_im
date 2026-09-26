package com.tm.im.storage.repository;

import com.tm.im.domain.entity.Media;
import com.tm.im.domain.repository.MediaRepository;
import com.tm.im.storage.mapper.MediaMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 媒体元数据仓储实现。
 *
 * <p>{@code media} 是非分片表，所以这里全是主键点查与插入——不需要事务，
 * 也不会触发 §8.7 里那些「不带分片键就广播 16 张表」的问题。
 *
 * <p>刻意<b>不</b>做「同一个 object_key 只允许一行」的约束：key 里的 id 就是主键，
 * 一次上传一个 id，重复 key 在结构上不可能出现。加一个唯一索引只会让
 * {@code insert} 多一个可能失败的分支，而那个分支永远无人处理。
 */
@Repository
public class MediaRepositoryImpl implements MediaRepository {

    private final MediaMapper mapper;

    public MediaRepositoryImpl(MediaMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Media insert(Media media) {
        mapper.insert(media);
        return media;
    }

    @Override
    public Optional<Media> findById(long mediaId) {
        return Optional.ofNullable(mapper.selectById(mediaId));
    }

    @Override
    public void delete(long mediaId) {
        mapper.deleteById(mediaId);
    }
}
