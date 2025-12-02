package com.spotipass.module;

import android.content.Context;
import android.content.SharedPreferences;

final class SpotiPassPrefs {

    private static volatile SpotiPassPrefs instance;

    private final SharedPreferences prefs;
    private volatile OnConfigChangeListener listener;

    static final class Config {
        final boolean enabled;
        final boolean loginDnsOnly;
        final String loginDnsRules;

        Config(
                boolean enabled,
                boolean loginDnsOnly,
                String loginDnsRules
        ) {
            this.enabled = enabled;
            this.loginDnsOnly = loginDnsOnly;
            this.loginDnsRules = loginDnsRules;
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
        boolean loginDnsOnly = prefs.getBoolean(SpotiPassKeys.KEY_LOGIN_DNS_ONLY, false);
        String loginDnsRules = prefs.getString(SpotiPassKeys.KEY_LOGIN_DNS_RULES, "");
        if (loginDnsRules == null) loginDnsRules = "";

        return new Config(enabled, loginDnsOnly, loginDnsRules);
    }

    void putConfig(
            Boolean enabled,
            Boolean loginDnsOnly,
            String loginDnsRules
    ) {
        SharedPreferences.Editor edit = prefs.edit();
        if (enabled != null) edit.putBoolean(SpotiPassKeys.KEY_ENABLED, enabled);
        if (loginDnsOnly != null) edit.putBoolean(SpotiPassKeys.KEY_LOGIN_DNS_ONLY, loginDnsOnly);
        if (loginDnsRules != null) edit.putString(SpotiPassKeys.KEY_LOGIN_DNS_RULES, loginDnsRules);
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
