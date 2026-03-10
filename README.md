# SpotiPass

**SpotiPass** 是一个NPatch/Xposed 模块。可以在不使用系统 VPN/VpnService、无 root 的情况下，修复 **Spotify** 在受限网络环境下的登录连接性问题。

同时提供反编译伪装包名的解决方案，以绕过部分系统的黑名单与后台管理。

## 实现原理

- DNS 拦截：覆盖 `InetAddress.getByName/getAllByName` 和 `okhttp3.Dns` 系统 DNS，继续作用于进程内 Java/OkHttp 登录请求
- 登录 DNS 模式：对应用内登录 WebView 启动本地回环代理，并通过 `WebView Proxy Override` 将登录相关请求导向 `127.0.0.1:随机端口`；本地代理读取 `CONNECT host:port` 后，按配置的 DNS 规则选择目标 IP 建立 TCP 隧道，TLS 仍由 WebView 与目标站点直接完成
- 登录 HTTP(S) 代理：对 `accounts.spotify.com`、`challenge.spotify.com`、`auth-callback.spotify.com` 等登录相关域名注入选择性 HTTP CONNECT 代理；对于应用内登录 WebView，不再让 Chromium 直接连接上游认证代理，而是先连接本地无认证 relay，再由 relay 转发到配置的 HTTP 或 HTTPS 代理服务器，并按需附带 Basic 认证；非登录流量保持直连
- reCAPTCHA 重写：将 `www.google.com/recaptcha/*` 替换为 `www.recaptcha.net/recaptcha/*`
- 登录流拦截：将外部浏览器/CustomTabs 登录页面改为应用内 WebView 展示

### 登录链路策略说明

- `登录 DNS`：仅使用 DNS 替换来实现登录，可能在某些网络情况下不成功，但简单直接，无需代理服务器。
- `登录代理`：适合你已经有一台可用出海网络的 HTTP(S) 代理服务器，不依赖网络质量。
- 两种模式都只针对登录相关域名生效。
- 两种模式都依赖当前设备 WebView 对 `PROXY_OVERRIDE` 的支持，才能让应用内登录 WebView 完整生效；如果 WebView 内核不支持，则 Java/OkHttp 链路仍可能部分生效，但 WebView 登录流无法完整接管。

## 构建

### SDK 配置

需要在项目根目录创建 `local.properties` 文件并指定 Android SDK 路径：

```properties
sdk.dir=C\:\\Users\\YOURNAME\\AppData\\Local\\Android\\Sdk
```

### 编译

#### 命令行编译（推荐）

配置完 SDK 后，可以直接运行

```powershell
# Debug 版本
.\gradlew.bat :app:assembleDebug

# Release 版本
.\gradlew.bat :app:assembleRelease
```

#### Android Studio 编译

用 Android Studio 打开 `SpotiPass`，构建 `app` 输出 APK（AGP 8.x 需要 JDK 17，且需配置本机 Android SDK）。

### 产物路径

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

## 通过 NPatch 集成到 Spotify

推荐优先使用安装了 NPatch 的 Android 手机进行集成，而不是桌面端 `jar` CLI。
原因有两点：

- `NPatch Releases` 中的 APK 更新通常快于 `npatch.jar`
- `jar` CLI 的处理行为看起来和 Android APK 版不完全一致，实际可能会打包出体积异常大的 APK

因此，更推荐把 Spotify APK 和 SpotiPass 模块 APK 传到安卓手机上，直接在 NPatch App 内完成嵌入。
如果还需要把 Spotify 改包名、伪装成 VMOS/AppLab 白名单应用，先在电脑上运行 `scripts/build_disguised.py` 生成伪装后的 Spotify APK，再把该 APK 传到手机继续集成。

### 伪装包名（可选）

在部分系统中，为了实现绕过黑名单，保留后台等目的，可以使用`scripts/build_disguised.py` 伪装包名。该脚本会修改 `package` 字段，并且同时处理 `AndroidManifest.xml` 中的相关引用、smali 里的裸包名字面量，以及命中 `forceBackgroundProcesses` 白名单时的 `ForegroundKeeperService` 子进程注入，避免单纯改包名后出现闪退。

**前置要求：**

- `scripts/apktool.jar`
- 系统可用的 `java`
- Android SDK build-tools 中的 `zipalign` 和 `apksigner`（脚本会从 `ANDROID_HOME` 或 `%USERPROFILE%\AppData\Local\Android\Sdk\build-tools` 自动查找）

```powershell
# 在仓库根目录执行
python .\scripts\build_disguised.py
```

交互式脚本的流程如下：

1. 选择输入源：直接读取 Spotify APK，或使用已经反编译好的目录。
2. 选择目标包名：推荐 `com.luna.music`，它命中 VMOS 的 `forceBackgroundProcesses` 白名单，脚本会额外把 `ForegroundKeeperService` 注入到 `:push` 进程。
3. 等待脚本完成反编译、改包名、回编译、`zipalign` 和签名。
4. 在 `scripts\` 目录获取输出 APK，例如 `scripts\spotify_com_luna_music.apk`。

### 在 Android 手机上使用 NPatch（推荐）

1. 从 [NPatch Releases](https://github.com/7723mod/NPatch/releases) 下载并安装最新的 NPatch APK。
2. 将 `Spotify APK`（原版或已伪装）和 `Spotipass APK` 传到手机。
3. 在 NPatch App 内选择 Spotify APK，并把 SpotiPass 模块嵌入进去。
4. 成功，你获得了 `修补后的 Spotify`。

### 交互式脚本（基于 NPatch jar 不推荐）

仅当你无法在 Android 设备上使用 NPatch 时，再选择该方案。由于 NPatch 的 jar 包 更新慢于 APK，且其 CLI 行为和 Android APK 版似乎存在差异，可能会生成体积明显偏大的 APK，不建议优先使用。

脚本会依次弹出文件选择对话框，让你选择 Spotify APK（原版或已伪装）、模块 APK 和 NPatch.jar。
注意：`scripts/patch-spotify.ps1` 的 `-NewPackage` 参数不可用，直接让 NPatch 改包名会遗漏兼容性处理，导致 Spotify 启动后闪退。

```powershell
./scripts/patch-spotify.ps1 -Force
```

## 使用

1. 安装 `修补后的 Spotify`
2. 打开 Spotify, 将出现 SpotiPass 悬浮窗
3. 点击悬浮窗，勾选“启用登录辅助”
4. 根据当前网络情况选择登录链路策略并保存：
5. 如果使用“登录 DNS”模式，填写 DNS 规则后保存。建议至少覆盖 `accounts.spotify.com`、`challenge.spotify.com`、`auth-callback.spotify.com`、`www.recaptcha.net`、`www.gstatic.com`、`www.gstatic.cn`。可配合 `scripts/probe-login-dns.ps1` 生成可用规则。
6. 如果使用“登录代理”模式，填写代理主机、端口，并按需启用“代理服务器使用 TLS”和用户名/密码；建议先点击“测试登录代理”确认可以成功建立到 `accounts.spotify.com:443` 的 CONNECT 隧道，再保存。
7. 登录：模块会自动拦截登录流程，在应用内 WebView 中完成登录；其中 Spotify 登录相关域名会按所选策略走 DNS 覆写或 HTTP(S) 代理链路。

注意，如果你使用的是 `登录 DNS` 模式，速度和成功率主要取决于你填写的 IP 质量；如果首个 IP 不可达，模块会按配置顺序自动尝试下一个候选 IP，并在日志中记录重试。

## 其他

### probe-login-dns.ps1 — 探测可达的 Spotify 登录 IP

该脚本通过多个公共 DNS 服务器解析 Spotify 登录相关域名，逐一测试每个 IP 的 TLS 可达性，最终输出可直接粘贴到 SpotiPass 配置界面的 DNS 规则。

```powershell
# 使用默认参数运行（推荐）
./scripts/probe-login-dns.ps1

# 自定义参数
./scripts/probe-login-dns.ps1 -DnsServers @("223.5.5.5","8.8.8.8") -TimeoutSec 10 -TopN 5
```

**参数说明：**

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `-Hosts` | Spotify 登录相关域名列表 | 要探测的目标域名 |
| `-DnsServers` | `223.5.5.5`, `119.29.29.29`, `8.8.8.8`, `1.1.1.1` | 用于解析的 DNS 服务器 |
| `-TimeoutSec` | `6` | 每个 IP 的 TLS 连接超时（秒） |
| `-TopN` | `3` | 每个域名最多输出的可达 IP 数 |

**前置要求：** 系统 PATH 中需要有 `curl.exe`（Windows 10+ 自带）。

脚本运行后会输出如下格式的 DNS 规则，可直接复制到 SpotiPass 配置界面中：

```
accounts.spotify.com=1.2.3.4,5.6.7.8
challenge.spotify.com=9.10.11.12
```

### 在 ImmortalWrt/HomeProxy 上启用 HTTPS 代理时的证书目录权限问题

在 ImmortalWrt 上使用 HomeProxy 创建服务端是个好主意，你只需要可用的域名和带有公网IP的家用宽带，而无需租赁云服务器。

如果你打算在 `ImmortalWrt` 上使用 `HomeProxy` 服务端创建 `HTTP(S)` 代理，并为代理入口域名启用 `TLS + ACME`（例如 `spotipass.example.com:12345`），可能会遇到服务无法启动的已知问题。

现象：

- `HomeProxy`/`sing-box` 服务端反复重启，进入 crash loop
- `logread` 中出现类似如下错误

```text
failed storage check: failed to create temp file: open /etc/homeproxy/certs/...: permission denied - storage is probably misconfigured
```

原因：

- `HomeProxy` 服务端中的 `sing-box` 进程通常以 `sing-box` 用户运行
- 开启 `ACME` 后，证书和缓存会写入 `/etc/homeproxy/certs`
- 某些环境下该目录默认由 `root:root` 创建，导致 `sing-box` 用户无法写入，从而在申请/加载证书时直接启动失败

解决方式：

```sh
mkdir -p /etc/homeproxy/certs
chown -R sing-box:sing-box /etc/homeproxy/certs
chmod 700 /etc/homeproxy/certs
chmod 711 /etc/homeproxy
/etc/init.d/homeproxy restart
```

修复后，可使用如下命令查看日志确认问题是否已解除：

```sh
logread -f | grep -E 'sing-box|homeproxy|acme|cloudflare|cert'
```
