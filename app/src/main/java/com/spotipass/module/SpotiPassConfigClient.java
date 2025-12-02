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

    private SpotiPassConfigClient() {}

    static Config get(Context context) {
        if (context == null) {
            return new Config(false, false, "");
        }
        try {
            Bundle out = context.getContentResolver().call(URI, METHOD_GET, null, null);
            if (out == null) {
                return new Config(false, false, "");
            }
            boolean enabled = out.getBoolean(SpotiPassKeys.KEY_ENABLED, false);
            boolean loginDnsOnly = out.getBoolean(SpotiPassKeys.KEY_LOGIN_DNS_ONLY, false);
            String loginDnsRules = out.getString(SpotiPassKeys.KEY_LOGIN_DNS_RULES, "");
            return new Config(
                    enabled,
                    loginDnsOnly,
                    loginDnsRules == null ? "" : loginDnsRules
            );
        } catch (Throwable ignored) {
            return new Config(false, false, "");
        }
    }

    static void setConfig(
            Context context,
            Boolean enabled,
            Boolean loginDnsOnly,
            String loginDnsRules
    ) {
        if (context == null) return;
        Bundle in = new Bundle();
        if (enabled != null) in.putBoolean(SpotiPassKeys.KEY_ENABLED, enabled);
        if (loginDnsOnly != null) in.putBoolean(SpotiPassKeys.KEY_LOGIN_DNS_ONLY, loginDnsOnly);
        if (loginDnsRules != null) in.putString(SpotiPassKeys.KEY_LOGIN_DNS_RULES, loginDnsRules);
        try {
            context.getContentResolver().call(URI, METHOD_SET, null, in);
        } catch (Throwable ignored) {
        }
    }
}
