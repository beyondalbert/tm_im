package com.tm.im.api.user.view;

import com.tm.im.core.media.MediaService;

/**
 * 上传成功的响应体（03-rest-api.md §5.1）。
 *
 * <p>{@code width}/{@code height} 是<b>可空</b>的：webp 无法在当前 JVM 里解码
 * （见 {@code MediaService} 的类注释），因此那两个字段是 {@code null}，
 * 而不是 {@code 0}——{@code 0} 会被客户端当成「一种真实的尺寸」画成一个 0×0 的框。
 *
 * <p>字段顺序与文档示例一致，便于逐字对照。
 */
public record MediaView(long mediaId,
                        String mime,
                        Integer width,
                        Integer height,
                        long sizeBytes,
                        String url,
                        String thumbUrl) {

    public static MediaView of(MediaService.Uploaded uploaded) {
        return new MediaView(
                uploaded.media().getId(),
                uploaded.media().getMime(),
                uploaded.media().getWidth(),
                uploaded.media().getHeight(),
                uploaded.media().getSizeBytes() == null ? 0L : uploaded.media().getSizeBytes(),
                uploaded.url(),
                uploaded.thumbUrl());
    }
}
