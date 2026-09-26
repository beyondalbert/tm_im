package com.tm.im.api.user.controller;

import com.tm.im.api.user.auth.CurrentActor;
import com.tm.im.api.user.view.MediaView;
import com.tm.im.common.api.ApiResponse;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.identity.AuthContext;
import com.tm.im.core.media.MediaService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 图片上传与下载（03-rest-api.md §5）。
 *
 * <p>这是本仓库里<b>唯一一个响应体不是 {@code ApiResponse} 信封</b>的控制器：
 * 下载接口要回二进制流，把图片字节塞进 JSON 的 base64 字段既浪费 33% 带宽，
 * 又让浏览器无法直接把它当 {@code <img src>} 用。失败时它仍然回信封
 * （{@code ApiExceptionHandler} 统一处理），所以「成功是流、失败是 JSON」
 * 这条不整齐的边界只存在于这两个方法里，且是 HTTP 的惯例（{@code Content-Type} 区分）。
 *
 * <p><b>控制器里没有任何校验</b>：大小、格式、尺寸、归属全在 {@link MediaService}。
 * 这里只做三件机械的事——把 multipart 取成字节、把 {@code thumb} 查询参数取成布尔、
 * 把字节包成带响应头的 {@code ResponseEntity}。
 */
@RestController
@RequestMapping("/v1/media")
public class MediaController {

    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    /**
     * §5.1 上传。
     *
     * <p>参数名固定 {@code file}（文档里的 {@code curl -F "file=@..."}）。
     * {@code required=false} 是为了让「忘了带 file 字段」走到我们自己的错误码：
     * Spring 若按必填处理，会抛 {@code MissingServletRequestPartException}
     * 并回一个没有 {@code code} 的响应体，客户端要为此写第二套解析逻辑。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MediaView> upload(@CurrentActor AuthContext caller,
                                        @RequestParam(name = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "缺少 file 字段（multipart/form-data）");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            // 读不出 multipart 的内容 = 请求本身没传完，属于客户端错误而不是 500。
            // 回 40000 让客户端重发，而不是让它以为服务端坏了。
            throw new TmException(ErrorCode.BAD_REQUEST, "读取上传内容失败: " + e.getMessage());
        }
        return ApiResponse.ok(MediaView.of(
                media.upload(caller.actorId(), file.getOriginalFilename(), bytes)));
    }

    /**
     * §5.2 下载。
     *
     * <p>三个响应头各自解决一个具体问题：
     * <ul>
     *   <li>{@code Content-Type} 来自<b>内容嗅探</b>（{@code media.mime}），
     *       不是客户端上传时声明的那一个——理由见 {@code MediaService} 的类注释；</li>
     *   <li>{@code X-Content-Type-Options: nosniff} 让浏览器不要「猜」类型。
     *       少了它，一个内容为 HTML 但被声明成图片的响应在某些老浏览器上仍会被渲染；</li>
     *   <li>{@code Cache-Control: public, max-age=31536000, immutable} 因为
     *       {@code media_id} 指向的内容<b>永不改变</b>（重新上传会得到新的 id）。
     *       一年 + immutable 可以让客户端与 CDN 都不再回源，代价是「删除图片后
     *       客户端仍可能展示缓存」——这个代价是正确且用户可接受的
     *       （删除媒体在未来要做的语义是「不再能取到」，而不是「抹掉别人屏幕上的像素」）。</li>
     * </ul>
     */
    @GetMapping("/{mediaId}")
    public ResponseEntity<byte[]> download(@CurrentActor AuthContext caller,
                                          @PathVariable long mediaId,
                                          @RequestParam(name = "thumb", required = false) Integer thumb) {
        MediaService.Content content = media.load(mediaId, isThumb(thumb));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mime()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .body(content.bytes());
    }

    /**
     * {@code ?thumb=1} 才算要缩略图。
     *
     * <p>只认 {@code 1}，不认 {@code true}/{@code yes}：文档里写的是 {@code thumb=1}，
     * 而「宽容地接受多种写法」会让两种客户端行为都通过测试，
     * 于是第三种客户端写 {@code t=1} 时没人发现它其实什么都不生效。
     */
    private static boolean isThumb(Integer thumb) {
        return thumb != null && thumb == 1;
    }
}
