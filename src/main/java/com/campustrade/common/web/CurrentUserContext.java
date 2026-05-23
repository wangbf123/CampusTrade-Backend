package com.campustrade.common.web;

import com.campustrade.common.exception.BizException;

public final class CurrentUserContext {

    private static final ThreadLocal<AuthenticatedUser> HOLDER = new ThreadLocal<>();

    private CurrentUserContext() {
    }

    public static void set(AuthenticatedUser user) {
        HOLDER.set(user);
    }

    public static AuthenticatedUser require() {
        AuthenticatedUser user = HOLDER.get();
        if (user == null) {
            throw BizException.unauthorized("请先登录");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
