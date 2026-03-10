package com.spotipass.module;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

final class SpotiPassConfigClient {

    private static final Uri URI = Uri.parse("content://" + SpotiPassKeys.AUTHORITY);

    private static final String METHOD_GET = "get";
    private static final String METHOD_SET = "set";

    static final class Config {
        final boolean enabled;
        final String loginMode;
        final boolean loginDnsOnly;
        final String loginDnsRules;
        final String loginProxyHost;
        final String loginProxyPort;
        final boolean loginProxyTls;
        final String loginProxyUsername;
        final String loginProxyPassword;

        Config(
                boolean enabled,
                String loginMode,
                String loginDnsRules,
                String loginProxyHost,
                String loginProxyPort,
                boolean loginProxyTls,
                String loginProxyUsername,
                String loginProxyPassword
        ) {
            this.enabled = enabled;
            this.loginMode = SpotiPassKeys.normalizeLoginMode(loginMode, false);
            this.loginDnsOnly = SpotiPassKeys.isLoginDnsMode(this.loginMode);
            this.loginDnsRules = loginDnsRules;
            this.loginProxyHost = loginProxyHost;
            this.loginProxyPort = loginProxyPort;
            this.loginProxyTls = loginProxyTls;
            this.loginProxyUsername = loginProxyUsername;
            this.loginProxyPassword = loginProxyPassword;
        }

        boolean isLoginProxyMode() {
            return SpotiPassKeys.isLoginProxyMode(loginMode);
        }
    }

    private SpotiPassConfigClient() {}

    static Config get(Context context) {
        if (context == null) {
            return new Config(false, SpotiPassKeys.LOGIN_MODE_NONE, "", "", "", false, "", "");
        }
        try {
            Bundle out = context.getContentResolver().call(URI, METHOD_GET, null, null);
            if (out == null) {
                return new Config(false, SpotiPassKeys.LOGIN_MODE_NONE, "", "", "", false, "", "");
            }
            boolean enabled = out.getBoolean(SpotiPassKeys.KEY_ENABLED, false);
            boolean legacyLoginDnsOnly = out.getBoolean(SpotiPassKeys.KEY_LOGIN_DNS_ONLY, false);
            String loginMode = SpotiPassKeys.normalizeLoginMode(
                    out.getString(SpotiPassKeys.KEY_LOGIN_MODE, null),
                    legacyLoginDnsOnly
            );
            String loginDnsRules = out.getString(SpotiPassKeys.KEY_LOGIN_DNS_RULES, "");
            String loginProxyHost = out.getString(SpotiPassKeys.KEY_LOGIN_PROXY_HOST, "");
            String loginProxyPort = out.getString(SpotiPassKeys.KEY_LOGIN_PROXY_PORT, "");
            boolean loginProxyTls = out.getBoolean(SpotiPassKeys.KEY_LOGIN_PROXY_TLS, false);
            String loginProxyUsername = out.getString(SpotiPassKeys.KEY_LOGIN_PROXY_USERNAME, "");
            String loginProxyPassword = out.getString(SpotiPassKeys.KEY_LOGIN_PROXY_PASSWORD, "");
            return new Config(
                    enabled,
                    loginMode,
                    loginDnsRules == null ? "" : loginDnsRules,
                    loginProxyHost == null ? "" : loginProxyHost,
                    loginProxyPort == null ? "" : loginProxyPort,
                    loginProxyTls,
                    loginProxyUsername == null ? "" : loginProxyUsername,
                    loginProxyPassword == null ? "" : loginProxyPassword
            );
        } catch (Throwable ignored) {
            return new Config(false, SpotiPassKeys.LOGIN_MODE_NONE, "", "", "", false, "", "");
        }
    }

    static void setConfig(
            Context context,
            Boolean enabled,
            String loginMode,
            String loginDnsRules,
            String loginProxyHost,
            String loginProxyPort,
            Boolean loginProxyTls,
            String loginProxyUsername,
            String loginProxyPassword
    ) {
        if (context == null) return;
        Bundle in = new Bundle();
        if (enabled != null) in.putBoolean(SpotiPassKeys.KEY_ENABLED, enabled);
        if (loginMode != null) {
            String normalizedMode = SpotiPassKeys.normalizeLoginMode(loginMode, false);
            in.putString(SpotiPassKeys.KEY_LOGIN_MODE, normalizedMode);
            in.putBoolean(SpotiPassKeys.KEY_LOGIN_DNS_ONLY, SpotiPassKeys.isLoginDnsMode(normalizedMode));
        }
        if (loginDnsRules != null) in.putString(SpotiPassKeys.KEY_LOGIN_DNS_RULES, loginDnsRules);
        if (loginProxyHost != null) in.putString(SpotiPassKeys.KEY_LOGIN_PROXY_HOST, loginProxyHost);
        if (loginProxyPort != null) in.putString(SpotiPassKeys.KEY_LOGIN_PROXY_PORT, loginProxyPort);
        if (loginProxyTls != null) in.putBoolean(SpotiPassKeys.KEY_LOGIN_PROXY_TLS, loginProxyTls);
        if (loginProxyUsername != null) in.putString(SpotiPassKeys.KEY_LOGIN_PROXY_USERNAME, loginProxyUsername);
        if (loginProxyPassword != null) in.putString(SpotiPassKeys.KEY_LOGIN_PROXY_PASSWORD, loginProxyPassword);
        try {
            context.getContentResolver().call(URI, METHOD_SET, null, in);
        } catch (Throwable ignored) {
        }
    }
}
