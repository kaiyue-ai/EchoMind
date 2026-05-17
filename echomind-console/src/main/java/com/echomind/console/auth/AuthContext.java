package com.echomind.console.auth;

/** 基于 ThreadLocal 的轻量请求身份上下文。 */
public final class AuthContext {

    private static final ThreadLocal<AuthUser> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(AuthUser user) {
        CURRENT.set(user == null ? AuthUser.DEFAULT : user);
    }

    public static AuthUser current() {
        AuthUser user = CURRENT.get();
        return user == null ? AuthUser.DEFAULT : user;
    }

    public static String userId() {
        return current().userId();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
