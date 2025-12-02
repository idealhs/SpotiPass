package com.spotipass.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class SpotiPassConfigActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        SpotiPassConfigClient.Config config = SpotiPassConfigClient.get(this);

        TextView statusView = findViewById(R.id.status_view);
        CheckBox enabledBox = findViewById(R.id.cb_enabled);
        CheckBox loginDnsOnlyBox = findViewById(R.id.cb_login_dns_only);
        EditText loginDnsRulesInput = findViewById(R.id.login_dns_rules_input);

        enabledBox.setChecked(config.enabled);
        loginDnsOnlyBox.setChecked(config.loginDnsOnly);
        if (config.loginDnsRules == null || config.loginDnsRules.trim().isEmpty()) {
            loginDnsRulesInput.setText(defaultLoginDnsRulesTemplate());
        } else {
            loginDnsRulesInput.setText(config.loginDnsRules);
        }

        findViewById(R.id.btn_save).setOnClickListener(v -> {
            SpotiPassConfigClient.setConfig(
                    this,
                    enabledBox.isChecked(),
                    loginDnsOnlyBox.isChecked(),
                    loginDnsRulesInput.getText().toString()
            );
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
            refreshStatusAsync(statusView);
        });

        findViewById(R.id.btn_view_log).setOnClickListener(v -> showLog());
        findViewById(R.id.btn_refresh_status).setOnClickListener(v -> refreshStatusAsync(statusView));

        refreshStatusAsync(statusView);
    }

    private void refreshStatusAsync(TextView statusView) {
        new Thread(() -> {
            String status;
            try {
                status = buildStatus();
            } catch (Throwable t) {
                status = "\u72b6\u6001\u8bfb\u53d6\u5931\u8d25\uff1a" + t.getMessage();
            }
            String finalStatus = status;
            runOnUiThread(() -> statusView.setText(finalStatus));
        }, "spotipass-config-status").start();
    }

    private String buildStatus() {
        SpotiPassConfigClient.Config config = SpotiPassConfigClient.get(this);
        StringBuilder sb = new StringBuilder();
        sb.append("\u767b\u5f55\u8f85\u52a9\uff1a").append(config.enabled ? "\u5df2\u542f\u7528" : "\u672a\u542f\u7528").append('\n');
        sb.append("\u6a21\u5f0f\uff1a").append(config.loginDnsOnly ? "\u4ec5\u767b\u5f55 DNS" : "\u517c\u5bb9\u6a21\u5f0f").append('\n');
        sb.append("DNS \u89c4\u5219\uff1a").append(countDnsRuleLines(config.loginDnsRules)).append(" \u6761").append('\n');
        sb.append("\u8fd0\u884c\u65e5\u5fd7\u7f13\u5b58\uff1a").append(SpotiPassRuntimeLog.length()).append(" \u5b57\u7b26");
        return sb.toString();
    }

    private void showLog() {
        new Thread(() -> {
            String log = SpotiPassRuntimeLog.readTail(96 * 1024);
            if (log == null || log.isEmpty()) {
                log = "\u6682\u65e0\u8fd0\u884c\u65e5\u5fd7\u3002\n\n"
                        + "\u8bf7\u5148\u5728\u76ee\u6807\u5e94\u7528\u5185\u8d70\u4e00\u904d\u767b\u5f55\u6d41\u7a0b\uff0c\u518d\u56de\u5230\u8fd9\u91cc\u67e5\u770b\u3002";
            }
            String finalLog = log;
            runOnUiThread(() -> {
                TextView tv = new TextView(this);
                tv.setText(finalLog);
                tv.setTextIsSelectable(true);
                tv.setTextSize(11f);
                tv.setTypeface(Typeface.MONOSPACE);

                ScrollView sv = new ScrollView(this);
                sv.addView(tv);

                new AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_log_title)
                        .setView(sv)
                        .setPositiveButton("\u5173\u95ed", null)
                        .show();
            });
        }, "spotipass-config-log").start();
    }

    private static int countDnsRuleLines(String rules) {
        if (rules == null || rules.isEmpty()) return 0;
        String normalized = rules.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int count = 0;
        for (String line : lines) {
            if (line == null) continue;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.contains("=")) count++;
        }
        return count;
    }

    private static String defaultLoginDnsRulesTemplate() {
        return "accounts.spotify.com=35.186.224.24\n"
                + "challenge.spotify.com=35.186.224.24\n"
                + "auth-callback.spotify.com=35.186.224.24\n"
                + "partner-accounts.spotify.com=35.186.224.24\n"
                + "accounts-gew4.spotify.com=35.186.224.28\n"
                + "accounts-gue1.spotify.com=35.186.224.9\n"
                + "accounts-gew1.spotify.com=35.186.224.26\n"
                + "accounts-guc3.spotify.com=35.186.224.31\n"
                + "accounts-gae2.spotify.com=35.186.224.22\n"
                + "www.recaptcha.net=120.253.255.98,120.253.250.226\n"
                + "www.gstatic.com=120.253.253.226,120.253.253.162,120.253.255.34\n";
    }
}
