# SpotiPass

**SpotiPass** 是一个NPatch/Xposed 模块。可以在不使用系统 VPN/VpnService、无 root 的情况下，修复 **Spotify** 在受限网络环境下的登录连接性问题。

## 实现原理

- DNS 拦截：覆盖 `InetAddress.getByName/getAllByName` 和 `okhttp3.Dns` 系统 DNS
- reCAPTCHA 重写：将 `www.google.com/recaptcha/*` 替换为 `www.recaptcha.net/recaptcha/*`
- 登录流拦截：将外部浏览器/CustomTabs 登录页面改为应用内 WebView 展示

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

从 [NPatch Releases](https://github.com/7723mod/NPatch/releases) 下载 `npatch.jar`
使用 NPatch 的 `--embed` 把模块 APK 嵌入到 Spotify APK，然后安装修补后的 Spotify。

### 交互式脚本（推荐）

脚本会依次弹出文件选择对话框，让你选择 Spotify APK、模块 APK 和 NPatch.jar。

```powershell
cd SpotiPass
./scripts/patch-spotify.ps1 -Force
```

### 直接使用 NPatch jar

```powershell
java -jar npatch.jar -m .\SpotiPass\app\build\outputs\apk\release\app-release.apk .\Spotify\spotify.apk
```

## 辅助脚本

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

## 使用

1. 用 NPatch 把模块嵌入 Spotify，安装修补后的 Spotify
2. 打开 Spotify, 将出现 SpotiPass 悬浮窗
3. 点击悬浮窗，勾选"启用登录辅助"和"仅登录 DNS 模式"→ 配置 DNS 规则 → 保存
4. 登录：模块会自动拦截登录流程，在应用内 WebView 中完成登录

注意，原生网络下 Spotify 的登录请求可能会非常慢，当人机验证结束后，需要耐心等待
