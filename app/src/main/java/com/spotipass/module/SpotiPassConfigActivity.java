package com.spotipass.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
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
        RadioGroup loginModeGroup = findViewById(R.id.rg_login_mode);
        LinearLayout loginDnsSection = findViewById(R.id.login_dns_section);
        LinearLayout loginProxySection = findViewById(R.id.login_proxy_section);
        EditText loginDnsRulesInput = findViewById(R.id.login_dns_rules_input);
        EditText loginProxyHostInput = findViewById(R.id.login_proxy_host_input);
        EditText loginProxyPortInput = findViewById(R.id.login_proxy_port_input);
        EditText loginProxyUsernameInput = findViewById(R.id.login_proxy_username_input);
        EditText loginProxyPasswordInput = findViewById(R.id.login_proxy_password_input);

        enabledBox.setChecked(config.enabled);
        loginModeGroup.check(loginModeToRadioId(config.loginMode));
        if (config.loginDnsRules == null || config.loginDnsRules.trim().isEmpty()) {
            loginDnsRulesInput.setText(defaultLoginDnsRulesTemplate());
        } else {
            loginDnsRulesInput.setText(config.loginDnsRules);
        }
        loginProxyHostInput.setText(config.loginProxyHost);
        loginProxyPortInput.setText(config.loginProxyPort);
        loginProxyUsernameInput.setText(config.loginProxyUsername);
        loginProxyPasswordInput.setText(config.loginProxyPassword);

        loginModeGroup.setOnCheckedChangeListener((group, checkedId) ->
                updateLoginModeSections(loginDnsSection, loginProxySection, checkedId));
        updateLoginModeSections(loginDnsSection, loginProxySection, loginModeGroup.getCheckedRadioButtonId());

        findViewById(R.id.btn_save).setOnClickListener(v -> {
            SpotiPassConfigClient.setConfig(
                    this,
                    enabledBox.isChecked(),
                    radioIdToLoginMode(loginModeGroup.getCheckedRadioButtonId()),
                    loginDnsRulesInput.getText().toString(),
                    loginProxyHostInput.getText().toString(),
                    loginProxyPortInput.getText().toString(),
                    loginProxyUsernameInput.getText().toString(),
                    loginProxyPasswordInput.getText().toString()
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
        sb.append("\u6a21\u5f0f\uff1a").append(loginModeLabel(config.loginMode)).append('\n');
        sb.append("DNS \u89c4\u5219\uff1a").append(countDnsRuleLines(config.loginDnsRules)).append(" \u6761").append('\n');
        sb.append("\u767b\u5f55\u4ee3\u7406\uff1a").append(formatLoginProxyTarget(config)).append('\n');
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

    private static int loginModeToRadioId(String loginMode) {
        if (SpotiPassKeys.isLoginDnsMode(loginMode)) return R.id.rb_login_mode_dns;
        if (SpotiPassKeys.isLoginProxyMode(loginMode)) return R.id.rb_login_mode_proxy;
        return R.id.rb_login_mode_none;
    }

    private static String radioIdToLoginMode(int checkedId) {
        if (checkedId == R.id.rb_login_mode_dns) return SpotiPassKeys.LOGIN_MODE_DNS;
        if (checkedId == R.id.rb_login_mode_proxy) return SpotiPassKeys.LOGIN_MODE_PROXY;
        return SpotiPassKeys.LOGIN_MODE_NONE;
    }

    private static void updateLoginModeSections(LinearLayout loginDnsSection, LinearLayout loginProxySection, int checkedId) {
        String loginMode = radioIdToLoginMode(checkedId);
        loginDnsSection.setVisibility(SpotiPassKeys.isLoginDnsMode(loginMode) ? LinearLayout.VISIBLE : LinearLayout.GONE);
        loginProxySection.setVisibility(SpotiPassKeys.isLoginProxyMode(loginMode) ? LinearLayout.VISIBLE : LinearLayout.GONE);
    }

    private static String loginModeLabel(String loginMode) {
        if (SpotiPassKeys.isLoginDnsMode(loginMode)) return "\u767b\u5f55 DNS";
        if (SpotiPassKeys.isLoginProxyMode(loginMode)) return "\u767b\u5f55\u4ee3\u7406";
        return "\u65e0";
    }

    private static String formatLoginProxyTarget(SpotiPassConfigClient.Config config) {
        String host = config.loginProxyHost == null ? "" : config.loginProxyHost.trim();
        String port = config.loginProxyPort == null ? "" : config.loginProxyPort.trim();
        if (host.isEmpty() || port.isEmpty()) return "\u672a\u914d\u7f6e";
        String suffix = hasProxyCredentials(config) ? "\uff08\u542b\u8ba4\u8bc1\uff09" : "";
        return host + ":" + port + suffix;
    }

    private static boolean hasProxyCredentials(SpotiPassConfigClient.Config config) {
        String username = config.loginProxyUsername == null ? "" : config.loginProxyUsername.trim();
        String password = config.loginProxyPassword == null ? "" : config.loginProxyPassword;
        return !username.isEmpty() || !password.isEmpty();
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
