package com.spotipass.module;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

public final class SpotiPassConfigProvider extends ContentProvider {

    private static final String METHOD_GET = "get";
    private static final String METHOD_SET = "set";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) return null;
        enforceCallerAllowed(context);

        SharedPreferences prefs = context.getSharedPreferences(SpotiPassKeys.PREFS, Context.MODE_PRIVATE);
        if (METHOD_GET.equals(method)) {
            Bundle out = new Bundle();
            out.putBoolean(SpotiPassKeys.KEY_ENABLED, prefs.getBoolean(SpotiPassKeys.KEY_ENABLED, false));
            out.putBoolean(SpotiPassKeys.KEY_LOGIN_DNS_ONLY, prefs.getBoolean(SpotiPassKeys.KEY_LOGIN_DNS_ONLY, false));
            out.putString(SpotiPassKeys.KEY_LOGIN_DNS_RULES, prefs.getString(SpotiPassKeys.KEY_LOGIN_DNS_RULES, ""));
            return out;
        }

        if (METHOD_SET.equals(method)) {
            if (extras != null) {
                SharedPreferences.Editor edit = prefs.edit();
                if (extras.containsKey(SpotiPassKeys.KEY_ENABLED)) {
                    edit.putBoolean(SpotiPassKeys.KEY_ENABLED, extras.getBoolean(SpotiPassKeys.KEY_ENABLED, false));
                }
                if (extras.containsKey(SpotiPassKeys.KEY_LOGIN_DNS_ONLY)) {
                    edit.putBoolean(SpotiPassKeys.KEY_LOGIN_DNS_ONLY, extras.getBoolean(SpotiPassKeys.KEY_LOGIN_DNS_ONLY, false));
                }
                if (extras.containsKey(SpotiPassKeys.KEY_LOGIN_DNS_RULES)) {
                    edit.putString(SpotiPassKeys.KEY_LOGIN_DNS_RULES, extras.getString(SpotiPassKeys.KEY_LOGIN_DNS_RULES, ""));
                }
                edit.apply();
            }
            // 通知观察者配置已变更
            Uri notifyUri = Uri.parse("content://" + SpotiPassKeys.AUTHORITY);
            context.getContentResolver().notifyChange(notifyUri, null);
            return Bundle.EMPTY;
        }

        return super.call(method, arg, extras);
    }

    private static void enforceCallerAllowed(Context context) {
        // 允许同进程调用（如 NPatch 环境中 hook 代码与 provider 同进程）
        int callingUid = Binder.getCallingUid();
        if (callingUid == android.os.Process.myUid()) {
            return;
        }
        // 对于跨进程调用，provider 已声明为 exported，允许所有调用方
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
