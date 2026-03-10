package com.spotipass.module;

import android.content.Context;
import android.content.SharedPreferences;

final class SpotiPassPrefs {

    private static volatile SpotiPassPrefs instance;

    private final SharedPreferences prefs;
    private volatile OnConfigChangeListener listener;

    static final class Config {
        final boolean enabled;
        final String loginMode;
        final boolean loginDnsOnly;
        final String loginDnsRules;
        final String loginProxyHost;
        final String loginProxyPort;
        final String loginProxyUsername;
        final String loginProxyPassword;

        Config(
                boolean enabled,
                String loginMode,
                String loginDnsRules,
                String loginProxyHost,
                String loginProxyPort,
                String loginProxyUsername,
                String loginProxyPassword
        ) {
            this.enabled = enabled;
            this.loginMode = SpotiPassKeys.normalizeLoginMode(loginMode, false);
            this.loginDnsOnly = SpotiPassKeys.isLoginDnsMode(this.loginMode);
            this.loginDnsRules = loginDnsRules;
            this.loginProxyHost = loginProxyHost;
            this.loginProxyPort = loginProxyPort;
            this.loginProxyUsername = loginProxyUsername;
            this.loginProxyPassword = loginProxyPassword;
        }

        boolean isLoginProxyMode() {
            return SpotiPassKeys.isLoginProxyMode(loginMode);
        }
    }

    interface OnConfigChangeListener {
        void onConfigChanged();
    }

    private SpotiPassPrefs(Context context) {
        this.prefs = context.getSharedPreferences("spotipass", Context.MODE_PRIVATE);
    }

    static SpotiPassPrefs getInstance(Context context) {
        if (instance == null) {
            synchronized (SpotiPassPrefs.class) {
                if (instance == null) {
                    instance = new SpotiPassPrefs(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    void setOnConfigChangeListener(OnConfigChangeListener listener) {
        this.listener = listener;
    }

    Config getConfig() {
        boolean enabled = prefs.getBoolean(SpotiPassKeys.KEY_ENABLED, false);
        boolean legacyLoginDnsOnly = prefs.getBoolean(SpotiPassKeys.KEY_LOGIN_DNS_ONLY, false);
        String loginMode = SpotiPassKeys.normalizeLoginMode(
                prefs.getString(SpotiPassKeys.KEY_LOGIN_MODE, null),
                legacyLoginDnsOnly
        );
        String loginDnsRules = prefs.getString(SpotiPassKeys.KEY_LOGIN_DNS_RULES, "");
        String loginProxyHost = prefs.getString(SpotiPassKeys.KEY_LOGIN_PROXY_HOST, "");
        String loginProxyPort = prefs.getString(SpotiPassKeys.KEY_LOGIN_PROXY_PORT, "");
        String loginProxyUsername = prefs.getString(SpotiPassKeys.KEY_LOGIN_PROXY_USERNAME, "");
        String loginProxyPassword = prefs.getString(SpotiPassKeys.KEY_LOGIN_PROXY_PASSWORD, "");
        if (loginDnsRules == null) loginDnsRules = "";
        if (loginProxyHost == null) loginProxyHost = "";
        if (loginProxyPort == null) loginProxyPort = "";
        if (loginProxyUsername == null) loginProxyUsername = "";
        if (loginProxyPassword == null) loginProxyPassword = "";

        return new Config(
                enabled,
                loginMode,
                loginDnsRules,
                loginProxyHost,
                loginProxyPort,
                loginProxyUsername,
                loginProxyPassword
        );
    }

    void putConfig(
            Boolean enabled,
            String loginMode,
            String loginDnsRules,
            String loginProxyHost,
            String loginProxyPort,
            String loginProxyUsername,
            String loginProxyPassword
    ) {
        SharedPreferences.Editor edit = prefs.edit();
        if (enabled != null) edit.putBoolean(SpotiPassKeys.KEY_ENABLED, enabled);
        if (loginMode != null) {
            String normalizedMode = SpotiPassKeys.normalizeLoginMode(loginMode, false);
            edit.putString(SpotiPassKeys.KEY_LOGIN_MODE, normalizedMode);
            edit.putBoolean(SpotiPassKeys.KEY_LOGIN_DNS_ONLY, SpotiPassKeys.isLoginDnsMode(normalizedMode));
        }
        if (loginDnsRules != null) edit.putString(SpotiPassKeys.KEY_LOGIN_DNS_RULES, loginDnsRules);
        if (loginProxyHost != null) edit.putString(SpotiPassKeys.KEY_LOGIN_PROXY_HOST, loginProxyHost);
        if (loginProxyPort != null) edit.putString(SpotiPassKeys.KEY_LOGIN_PROXY_PORT, loginProxyPort);
        if (loginProxyUsername != null) edit.putString(SpotiPassKeys.KEY_LOGIN_PROXY_USERNAME, loginProxyUsername);
        if (loginProxyPassword != null) edit.putString(SpotiPassKeys.KEY_LOGIN_PROXY_PASSWORD, loginProxyPassword);
        edit.apply();
        notifyChanged();
    }

    boolean isFirstLaunch() {
        return !prefs.contains(SpotiPassKeys.KEY_ENABLED);
    }

    private void notifyChanged() {
        OnConfigChangeListener l = listener;
        if (l != null) {
            l.onConfigChanged();
        }
    }
}
