package com.tm.im.api.admin.controller;

import com.tm.im.api.admin.auth.CurrentAdmin;
import com.tm.im.api.admin.view.AdminDeletedView;
import com.tm.im.api.admin.view.AdminViews;
import com.tm.im.api.admin.view.PostAdminView;
import com.tm.im.api.admin.view.PostPageView;
import com.tm.im.common.api.ApiResponse;
import com.tm.im.core.admin.AdminContext;
import com.tm.im.core.admin.AdminService;
import com.tm.im.domain.entity.Post;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;

/**
 * 内容管理（审核）。
 *
 * <p>只有两个动作：看最新的动态、删掉某一条。没有「编辑」——
 * 审核改动用户内容会让「谁说的」这件事失去意义，而删除是明确的：
 * 那条动态不存在了（{@code 40404}）。这与产品上「删帖而不是改帖」的取舍一致。
 *
 * <p>删除走的是作者删帖那条级联路径（{@code PlazaService#deleteAsAdmin}），
 * 所以收件箱、点赞、评论一起清——后台与用户端不会出现两套删除语义。
 */
@RestController
@RequestMapping("/v1/admin/posts")
public class AdminContentController {

    private final AdminService admins;
    private final ZoneId databaseZone;

    public AdminContentController(AdminService admins, ZoneId databaseZone) {
        this.admins = admins;
        this.databaseZone = databaseZone;
    }

    @GetMapping
    public ApiResponse<PostPageView> list(@CurrentAdmin AdminContext caller,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) String cursor) {
        AdminService.Page<Post> page = admins.listPosts(caller, limit == null ? 0 : limit, cursor);
        return ApiResponse.ok(new PostPageView(
                AdminViews.posts(page.items(), databaseZone), page.nextCursor(), page.hasMore()));
    }

    /**
     * 删帖。{@code reason} 作为查询参数（而不是请求体）：
     * {@code DELETE} 带 body 在部分代理上会被丢掉，而丢掉的是一个可选的原因，
     * 表现为「审计里查不到为什么删」——那种缺失不会报错。
     */
    @DeleteMapping("/{postId}")
    public ApiResponse<AdminDeletedView> delete(@CurrentAdmin AdminContext caller,
                                                @PathVariable long postId,
                                                @RequestParam(required = false) String reason) {
        admins.deletePost(caller, postId, reason);
        return ApiResponse.ok(new AdminDeletedView(AdminService.TARGET_POST, postId, true));
    }
}
