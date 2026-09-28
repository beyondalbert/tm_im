package com.tm.im.api.admin.view;

/** 新建后台账号（只有 SUPER 能调）。 */
public record AdminCreateRequest(String username, String password, String displayName,
                                Integer role) {
}
