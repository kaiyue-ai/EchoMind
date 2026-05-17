package com.echomind.console.auth;

/** 当前请求中的用户身份。 */
public record AuthUser(String userId, String username, boolean authenticated) {

    public static final String DEFAULT_USER_ID = "default";
    public static final AuthUser DEFAULT = new AuthUser(DEFAULT_USER_ID, "default", false);
}
