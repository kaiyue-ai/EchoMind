package com.echomind.console.admin;

/** 管理端请求身份上下文。 */
public final class AdminContext {

    private static final ThreadLocal<AdminUser> CURRENT = new ThreadLocal<>();

    private AdminContext() {
    }

    public static void set(AdminUser user) {
        CURRENT.set(user);
    }

    public static AdminUser current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
