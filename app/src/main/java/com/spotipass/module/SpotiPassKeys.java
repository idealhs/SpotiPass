package com.spotipass.module;

import java.util.Locale;

final class SpotiPassKeys {

    static final String AUTHORITY = "com.spotipass.module.config";
    static final String PREFS = "spotipass";

    static final String KEY_ENABLED = "enabled";
    static final String KEY_LOGIN_MODE = "login_mode";
    static final String KEY_LOGIN_DNS_ONLY = "login_dns_only";
    static final String KEY_LOGIN_DNS_RULES = "login_dns_rules";
    static final String KEY_LOGIN_PROXY_HOST = "login_proxy_host";
    static final String KEY_LOGIN_PROXY_PORT = "login_proxy_port";
    static final String KEY_LOGIN_PROXY_TLS = "login_proxy_tls";
    static final String KEY_LOGIN_PROXY_USERNAME = "login_proxy_username";
    static final String KEY_LOGIN_PROXY_PASSWORD = "login_proxy_password";

    static final String LOGIN_MODE_NONE = "none";
    static final String LOGIN_MODE_DNS = "dns";
    static final String LOGIN_MODE_PROXY = "proxy";

    private SpotiPassKeys() {}

    static String normalizeLoginMode(String loginMode, boolean legacyLoginDnsOnly) {
        if (loginMode != null) {
            String normalized = loginMode.trim().toLowerCase(Locale.US);
            if (LOGIN_MODE_DNS.equals(normalized)
                    || LOGIN_MODE_PROXY.equals(normalized)
                    || LOGIN_MODE_NONE.equals(normalized)) {
                return normalized;
            }
        }
        return legacyLoginDnsOnly ? LOGIN_MODE_DNS : LOGIN_MODE_NONE;
    }

    static boolean isLoginDnsMode(String loginMode) {
        return LOGIN_MODE_DNS.equals(loginMode);
    }

    static boolean isLoginProxyMode(String loginMode) {
        return LOGIN_MODE_PROXY.equals(loginMode);
    }
}
