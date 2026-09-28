package com.tm.im.api.admin.view;

/** 停用 / 启用一个后台账号（1=ACTIVE 2=DISABLED）。 */
public record AdminStatusRequest(Integer status) {
}
