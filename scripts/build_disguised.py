#!/usr/bin/env python3
"""
Spotify APK Package Name Disguiser for VMOS/AppLab
===================================================
Interactive build script that renames Spotify's package to a whitelisted
package name, allowing it to receive background keep-alive treatment
inside the AppLab VMOS container.

Usage:
    python build_disguised.py
"""

import os
import sys
import re
import shutil
import subprocess
import textwrap
from pathlib import Path

# ── Paths ────────────────────────────────────────────────────────────────────

SCRIPT_DIR = Path(__file__).resolve().parent
APKTOOL_JAR = SCRIPT_DIR / "apktool.jar"
KEYSTORE = SCRIPT_DIR / "spotify.keystore"
KEYSTORE_PASS = "android"
KEY_ALIAS = "spotify"

# Android SDK build-tools (pick highest version available)
SDK_BT = Path(os.environ.get("ANDROID_HOME", "")) / "build-tools"
if not SDK_BT.exists():
    SDK_BT = Path.home() / "AppData/Local/Android/Sdk/build-tools"

def find_build_tool(name):
    """Find the newest version of an Android build tool."""
    if not SDK_BT.exists():
        return None
    versions = sorted(SDK_BT.iterdir(), reverse=True)
    for v in versions:
        candidate = v / (name + ".exe")
        if candidate.exists():
            return str(candidate)
        candidate = v / (name + ".bat")
        if candidate.exists():
            return str(candidate)
    return None

ZIPALIGN = find_build_tool("zipalign")
APKSIGNER = find_build_tool("apksigner")

# ── Whitelist Data ───────────────────────────────────────────────────────────
#
# Keep-alive mechanism analysis:
#
# | Whitelist              | Effect                                    | Keep-alive? |
# |------------------------|-------------------------------------------|-------------|
# | forceBackgroundProcesses | VMOS native cgroup priority protection  | YES ✅      |
# | pluginAppPackage       | Inject frameworkpatch (ExoPlayer/display)  | No          |
# | specialPkgs            | Block audio focus release (some models)    | No (indirect)|
# | appPackage             | Video app audio ducking                    | No          |
# | focusAudioPackage      | Pause internal player                      | No          |
#
# The ONLY effective keep-alive mechanism is forceBackgroundProcesses.
# It matches process names in "pkg:subprocess" format.

# Each entry: (package_name, description, [whitelists], keep_alive_process)
# keep_alive_process: the subprocess name in forceBackgroundProcesses, or None
WHITELIST_PACKAGES = [
    # ═══ RECOMMENDED: forceBackgroundProcesses (real keep-alive) ═══════════
    ("com.luna.music",
     "Luna Music — music player, best match for Spotify",
     ["forceBackgroundProcesses"],
     ":push"),  # forceBackgroundProcesses has "com.luna.music:push"

    # --- forceBackgroundProcesses + other lists (video/social apps) ---
    ("com.ss.android.ugc.aweme",
     "Douyin (TikTok CN)",
     ["forceBackgroundProcesses", "appPackage", "focusAudioPackage", "specialPkgs"],
     ":push"),

    ("com.tencent.qqlive",
     "Tencent Video",
     ["forceBackgroundProcesses", "appPackage", "focusAudioPackage"],
     ":push"),

    ("com.youku.phone",
     "Youku",
     ["forceBackgroundProcesses", "appPackage", "focusAudioPackage"],
     ":push"),

    ("com.tencent.mm",
     "WeChat",
     ["forceBackgroundProcesses", "specialPkgs"],
     ":push"),

    # ═══ pluginAppPackage (frameworkpatch injection — NO keep-alive) ═══════
    ("cn.kuwo.player",
     "Kuwo Music",
     ["pluginAppPackage", "specialPkgs"],
     None),

    ("com.apple.android.music",
     "Apple Music",
     ["pluginAppPackage"],
     None),

    ("com.cctv.yangshipin.app.androidp",
     "CCTV Yangshipin",
     ["pluginAppPackage", "appPackage", "focusAudioPackage"],
     None),

    ("com.ximalaya.ting.android",
     "Ximalaya FM",
     ["pluginAppPackage", "specialPkgs"],
     None),

    # ═══ specialPkgs only (audio focus — NO keep-alive) ═══════════════════
    ("com.kugou.android",
     "Kugou Music",
     ["specialPkgs"],
     None),

    ("bubei.tingshu",
     "Bubei Tingshu",
     ["specialPkgs"],
     None),

    ("com.tencent.karaoke",
     "QQ Music Karaoke",
     ["specialPkgs"],
     None),

    ("com.sina.weibo",
     "Sina Weibo",
     ["specialPkgs"],
     None),

    # ═══ appPackage / focusAudioPackage only (NO keep-alive) ══════════════
    ("tv.danmaku.bili",
     "Bilibili",
     ["appPackage"],
     None),

    ("com.qiyi.video",
     "iQIYI",
     ["appPackage", "focusAudioPackage"],
     None),

    ("com.hunantv.imgo.activity",
     "Mango TV",
     ["appPackage", "focusAudioPackage"],
     None),
]

ORIGINAL_PKG = "com.spotify.music"

# ── UI Helpers ───────────────────────────────────────────────────────────────

def clear():
    os.system("cls" if os.name == "nt" else "clear")

def header(title):
    w = 64
    print("=" * w)
    print(f"  {title}")
    print("=" * w)

def ask_choice(prompt, options, allow_custom=False):
    """Display numbered options and return the chosen value."""
    for i, (label, _desc) in enumerate(options, 1):
        print(f"  [{i}] {label}")
        if _desc:
            for line in textwrap.wrap(_desc, 56):
                print(f"      {line}")
    if allow_custom:
        print(f"  [0] Custom input...")
    print()
    while True:
        raw = input(prompt).strip()
        if not raw:
            continue
        if allow_custom and raw == "0":
            return input("  Enter custom value: ").strip()
        try:
            idx = int(raw)
            if 1 <= idx <= len(options):
                return options[idx - 1][0]
        except ValueError:
            pass
        print("  Invalid choice, try again.")

# ── Core Logic ───────────────────────────────────────────────────────────────

def decompile_apk(apk_path, output_dir):
    """Run apktool d."""
    print(f"\n[*] Decompiling {apk_path} ...")
    cmd = ["java", "-jar", str(APKTOOL_JAR), "d", str(apk_path),
           "-o", str(output_dir), "-f"]
    r = subprocess.run(cmd, cwd=str(SCRIPT_DIR))
    if r.returncode != 0:
        print("[!] apktool decompile failed.")
        sys.exit(1)
    print("[+] Decompile OK")

def patch_manifest(manifest_path, old_pkg, new_pkg):
    """Replace package name and related references in AndroidManifest.xml."""
    with open(manifest_path, "r", encoding="utf-8") as f:
        content = f.read()

    old = old_pkg

    # 1. package attribute
    content = content.replace(f'package="{old}"', f'package="{new_pkg}"')

    # 2. Provider authorities  (android:authorities="com.spotify.music.XXX")
    content = content.replace(
        f'android:authorities="{old}.', f'android:authorities="{new_pkg}.')

    # 3. Custom permissions
    for suffix in [
        ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        ".permission.C2D_MESSAGE",
        ".permission.INTERNAL_BROADCAST",
        ".permission.SECURED_BROADCAST",
    ]:
        content = content.replace(f'"{old}{suffix}"', f'"{new_pkg}{suffix}"')

    # 4. Facebook redirect scheme
    content = content.replace(f"cct.{old}", f"cct.{new_pkg}")

    # 5. taskAffinity
    content = content.replace(
        f'android:taskAffinity="{old}.', f'android:taskAffinity="{new_pkg}.')

    # 6. Intent action names
    for action in ["ACTION_EXTERNAL_LOGIN", "ACTION_PREPARE", "ACTION_ALARM_WARMUP"]:
        content = content.replace(f'"{old}.{action}"', f'"{new_pkg}.{action}"')

    # 7. Meta-data name prefix
    content = content.replace(
        f'android:name="{old}.githash"', f'android:name="{new_pkg}.githash"')

    with open(manifest_path, "w", encoding="utf-8") as f:
        f.write(content)

    # Report remaining references (should only be Java class names)
    remaining = sum(1 for line in content.split("\n") if old in line)
    print(f"    Manifest patched.  Remaining '{old}' refs: {remaining} (class names, expected)")

def patch_smali(smali_root, old_pkg, new_pkg):
    """Replace bare package name const-strings in smali."""
    old = old_pkg
    # Pattern: const-string vX, "com.spotify.music"
    bare_re = re.compile(r'(const-string[^,]*,\s*)"' + re.escape(old) + r'"')
    bare_repl = rf'\1"{new_pkg}"'

    action_pairs = [
        (f'"{old}.ACTION_EXTERNAL_LOGIN"',  f'"{new_pkg}.ACTION_EXTERNAL_LOGIN"'),
        (f'"{old}.ACTION_ALARM_WARMUP"',    f'"{new_pkg}.ACTION_ALARM_WARMUP"'),
        (f'"{old}.ACTION_PREPARE"',         f'"{new_pkg}.ACTION_PREPARE"'),
    ]

    total_bare = 0
    total_action = 0
    file_count = 0

    for root, _dirs, files in os.walk(smali_root):
        for fname in files:
            if not fname.endswith(".smali"):
                continue
            fpath = os.path.join(root, fname)
            with open(fpath, "r", encoding="utf-8") as f:
                data = f.read()

            original = data
            data, n = bare_re.subn(bare_repl, data)
            total_bare += n

            for old_s, new_s in action_pairs:
                c = data.count(old_s)
                if c:
                    data = data.replace(old_s, new_s)
                    total_action += c

            if data != original:
                file_count += 1
                with open(fpath, "w", encoding="utf-8") as f:
                    f.write(data)

    print(f"    Smali patched: {total_bare} bare pkg refs, "
          f"{total_action} ACTION refs, across {file_count} files")

def inject_push_process(manifest_path, process_name):
    """Add android:process attribute to ForegroundKeeperService in manifest.

    This makes the service run as a named subprocess (e.g. ":push"),
    which matches forceBackgroundProcesses entries like "com.luna.music:push"
    and receives VMOS native-layer cgroup priority protection.
    """
    with open(manifest_path, "r", encoding="utf-8") as f:
        content = f.read()

    # Target: the ForegroundKeeperService <service> tag
    svc_tag = (
        'android:name="com.spotify.musicappplatform.state.'
        'foregroundkeeperservice.impl.ForegroundKeeperService"'
    )

    if svc_tag not in content:
        print(f"    [!] ForegroundKeeperService not found in manifest, skipping process injection")
        return False

    # Check if android:process is already set on this service
    # We look for the service tag and check if process attr exists nearby
    svc_pattern = re.compile(
        r'(<service\s[^>]*' + re.escape(svc_tag) + r')([^>]*/>|[^>]*>)',
        re.DOTALL,
    )
    m = svc_pattern.search(content)
    if not m:
        print(f"    [!] Could not parse ForegroundKeeperService tag, skipping")
        return False

    full_tag = m.group(0)
    if 'android:process=' in full_tag:
        print(f"    ForegroundKeeperService already has android:process, skipping")
        return True

    # Insert android:process=":push" before the closing /> or >
    # We insert it right before the end of the opening tag
    inject_attr = f'\n    android:process="{process_name}"'

    # Find the last attribute before closing
    if full_tag.rstrip().endswith('/>'):
        new_tag = full_tag.rstrip()[:-2] + inject_attr + '/>'
    else:
        # ends with >
        new_tag = full_tag.rstrip()[:-1] + inject_attr + '>'

    content = content.replace(full_tag, new_tag)

    with open(manifest_path, "w", encoding="utf-8") as f:
        f.write(content)

    print(f"    Injected android:process=\"{process_name}\" into ForegroundKeeperService")
    return True

def rebuild_apk(decompiled_dir, output_apk):
    """Run apktool b."""
    print(f"\n[*] Rebuilding APK ...")
    cmd = ["java", "-jar", str(APKTOOL_JAR), "b", str(decompiled_dir),
           "-o", str(output_apk)]
    r = subprocess.run(cmd, cwd=str(SCRIPT_DIR))
    if r.returncode != 0:
        print("[!] apktool build failed.")
        sys.exit(1)
    print("[+] Build OK")

def ensure_keystore():
    """Generate keystore if it doesn't exist."""
    if KEYSTORE.exists():
        return
    print("[*] Generating signing keystore ...")
    cmd = [
        "keytool", "-genkey", "-v",
        "-keystore", str(KEYSTORE),
        "-alias", KEY_ALIAS,
        "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10000",
        "-storepass", KEYSTORE_PASS,
        "-keypass", KEYSTORE_PASS,
        "-dname", "CN=Spotify,OU=Mobile,O=Spotify,L=Stockholm,ST=Stockholm,C=SE",
    ]
    subprocess.run(cmd, check=True)

def align_and_sign(input_apk, output_apk):
    """zipalign + apksigner."""
    if not ZIPALIGN or not APKSIGNER:
        print("[!] Cannot find zipalign / apksigner in Android SDK build-tools.")
        print(f"    Searched: {SDK_BT}")
        print("    Please set ANDROID_HOME or install Android SDK build-tools.")
        sys.exit(1)

    aligned = str(input_apk).replace(".apk", "_aligned.apk")

    print("[*] Aligning ...")
    subprocess.run([ZIPALIGN, "-f", "-v", "4", str(input_apk), aligned],
                   check=True, stdout=subprocess.DEVNULL)

    ensure_keystore()

    print("[*] Signing ...")
    subprocess.run([
        APKSIGNER, "sign",
        "--ks", str(KEYSTORE),
        "--ks-key-alias", KEY_ALIAS,
        "--ks-pass", f"pass:{KEYSTORE_PASS}",
        "--key-pass", f"pass:{KEYSTORE_PASS}",
        aligned,
    ], check=True)

    # Rename to final output
    final = str(output_apk)
    if os.path.exists(final):
        os.remove(final)
    os.rename(aligned, final)
    print(f"[+] Signed APK: {final}")
    # Clean up unsigned
    if os.path.exists(str(input_apk)):
        os.remove(str(input_apk))

# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    clear()
    header("Spotify APK Package Disguiser for AppLab/VMOS")
    print()

    # ── Step 1: Choose source ────────────────────────────────────────────
    print("[Step 1] Choose source:\n")
    source_mode = ask_choice("  Choice: ", [
        ("decompiled", "Use existing decompiled directory (skip apktool d)"),
        ("apk",        "Decompile from APK file (run apktool d first)"),
    ])

    if source_mode == "apk":
        apk_input = input("\n  Path to Spotify APK: ").strip().strip('"')
        if not os.path.isfile(apk_input):
            print(f"[!] File not found: {apk_input}")
            sys.exit(1)
        work_dir = SCRIPT_DIR / "spotifyde_work"
        decompile_apk(apk_input, work_dir)
    else:
        default_dir = SCRIPT_DIR / "spotifyde"
        hint = f" (default: {default_dir})" if default_dir.exists() else ""
        raw = input(f"\n  Path to decompiled dir{hint}: ").strip().strip('"')
        if not raw and default_dir.exists():
            work_dir = default_dir
        else:
            work_dir = Path(raw)
        if not work_dir.is_dir():
            print(f"[!] Directory not found: {work_dir}")
            sys.exit(1)

    manifest = work_dir / "AndroidManifest.xml"
    if not manifest.exists():
        print(f"[!] No AndroidManifest.xml in {work_dir}")
        sys.exit(1)

    # Detect current package name
    with open(manifest, "r", encoding="utf-8") as f:
        mtext = f.read()
    m = re.search(r'package="([^"]+)"', mtext)
    current_pkg = m.group(1) if m else "unknown"
    print(f"\n  Current package: {current_pkg}")

    # ── Step 2: Choose target package ────────────────────────────────────
    print(f"\n[Step 2] Choose target package name:\n")

    # Build a lookup for keep-alive process info
    pkg_process_map = {}  # pkg -> process_name or None
    for pkg, _desc, _lists, proc in WHITELIST_PACKAGES:
        pkg_process_map[pkg] = proc

    # Categorize by actual keep-alive effectiveness
    keepalive_pkgs = []   # forceBackgroundProcesses (real keep-alive)
    plugin_pkgs = []      # pluginAppPackage (no keep-alive)
    audio_pkgs = []       # specialPkgs only (no keep-alive)
    other_pkgs = []       # appPackage/focusAudioPackage only

    for pkg, desc, lists, proc in WHITELIST_PACKAGES:
        tag = ", ".join(lists)
        if proc:
            tag += f"  ->  process{proc}"
        entry = (pkg, f"{desc}  [{tag}]")
        if "forceBackgroundProcesses" in lists:
            keepalive_pkgs.append(entry)
        elif "pluginAppPackage" in lists:
            plugin_pkgs.append(entry)
        elif "specialPkgs" in lists:
            audio_pkgs.append(entry)
        else:
            other_pkgs.append(entry)

    # Print them all with numbers, keep-alive first
    sections = [
        ("*** forceBackgroundProcesses (REAL KEEP-ALIVE via VMOS cgroup) ***", keepalive_pkgs),
        ("pluginAppPackage (frameworkpatch injection, NO keep-alive)", plugin_pkgs),
        ("specialPkgs (audio focus only, NO keep-alive)", audio_pkgs),
        ("appPackage / focusAudioPackage (NO keep-alive)", other_pkgs),
    ]
    flat = []
    idx = 1
    for title, entries in sections:
        print(f"\n  -- {title} --")
        for pkg, desc in entries:
            # Highlight the recommended option
            marker = " <<<< RECOMMENDED" if pkg == "com.luna.music" else ""
            print(f"  [{idx:2d}] {pkg}{marker}")
            print(f"       {desc}")
            flat.append(pkg)
            idx += 1
    print(f"\n  [ 0] Custom package name...")
    print()

    while True:
        raw = input("  Choice: ").strip()
        if raw == "0":
            new_pkg = input("  Enter custom package name: ").strip()
            break
        try:
            ci = int(raw)
            if 1 <= ci <= len(flat):
                new_pkg = flat[ci - 1]
                break
        except ValueError:
            pass
        print("  Invalid, try again.")

    if new_pkg == current_pkg:
        print(f"\n[!] Target package is the same as current ({current_pkg}), nothing to do.")
        sys.exit(0)

    print(f"\n  Target package: {new_pkg}")

    # ── Step 3: Confirm ──────────────────────────────────────────────────
    # Look up keep-alive process for chosen package
    target_process = pkg_process_map.get(new_pkg)

    print(f"\n[Step 3] Confirm:")
    print(f"  Source dir:  {work_dir}")
    print(f"  {current_pkg}  -->  {new_pkg}")
    if target_process:
        print(f"  Keep-alive:  forceBackgroundProcesses ({new_pkg}{target_process})")
        print(f"  Process injection: ForegroundKeeperService -> android:process=\"{target_process}\"")
    else:
        print(f"  Keep-alive:  NONE (this package is NOT in forceBackgroundProcesses)")
        print(f"  WARNING: Without forceBackgroundProcesses, background survival is not guaranteed!")
    ans = input("\n  Proceed? [Y/n] ").strip().lower()
    if ans and ans != "y":
        print("  Aborted.")
        sys.exit(0)

    # ── Step 3.5: Copy work dir if source is the original ─────────────
    # To avoid modifying the original decompiled dir, we copy it
    if source_mode == "decompiled":
        safe_name = new_pkg.replace(".", "_")
        copy_dir = SCRIPT_DIR / f"spotifyde_{safe_name}"
        if copy_dir.exists():
            print(f"\n[*] Removing old work dir: {copy_dir}")
            shutil.rmtree(copy_dir)
        print(f"[*] Copying {work_dir} -> {copy_dir} ...")
        shutil.copytree(work_dir, copy_dir)
        work_dir = copy_dir
        manifest = work_dir / "AndroidManifest.xml"

    # ── Step 4: Patch ────────────────────────────────────────────────────
    # Determine the original package to replace
    # Re-read manifest in case we're working on a copy
    with open(manifest, "r", encoding="utf-8") as f:
        mtext = f.read()
    m = re.search(r'package="([^"]+)"', mtext)
    src_pkg = m.group(1) if m else ORIGINAL_PKG

    print(f"\n[Step 4] Patching (replacing {src_pkg} -> {new_pkg}) ...")

    print("  [4a] Patching AndroidManifest.xml ...")
    patch_manifest(manifest, src_pkg, new_pkg)

    print("  [4b] Patching smali files ...")
    patch_smali(str(work_dir), src_pkg, new_pkg)

    # 4c: Inject subprocess if target has forceBackgroundProcesses entry
    if target_process:
        print(f"  [4c] Injecting android:process=\"{target_process}\" into ForegroundKeeperService ...")
        inject_push_process(manifest, target_process)

    # ── Step 5: Rebuild ──────────────────────────────────────────────────
    safe_name = new_pkg.replace(".", "_")
    raw_apk = SCRIPT_DIR / f"spotify_{safe_name}_unsigned.apk"
    final_apk = SCRIPT_DIR / f"spotify_{safe_name}.apk"

    rebuild_apk(work_dir, raw_apk)

    # ── Step 6: Align & Sign ─────────────────────────────────────────────
    print("\n[Step 5] Align & Sign ...")
    align_and_sign(raw_apk, final_apk)

    # ── Done ─────────────────────────────────────────────────────────────
    size_mb = final_apk.stat().st_size / (1024 * 1024)
    print()
    header("Done!")
    print(f"""
  Output APK : {final_apk}
  Size       : {size_mb:.1f} MB
  Package    : {new_pkg}
  Signed with: {KEYSTORE}""")

    if target_process:
        print(f"""
  Keep-alive : forceBackgroundProcesses ({new_pkg}{target_process})
  Process    : ForegroundKeeperService -> android:process="{target_process}"
""")
        print(f"  Verification steps:")
        print(f"    1. Install to VMOS:  adb install \"{final_apk}\"")
        print(f"    2. Launch Spotify, login, play music")
        print(f"    3. Verify processes:")
        print(f"       adb shell ps | grep {new_pkg.split('.')[-1]}")
        print(f"       Expected: {new_pkg} (main) + {new_pkg}{target_process}")
        print(f"    4. Switch away, wait 5-10 min, check both processes survive")
        print(f"    5. The {target_process} process should be protected by VMOS cgroup")
    else:
        print(f"""
  WARNING: {new_pkg} is NOT in forceBackgroundProcesses.
  Background keep-alive is NOT guaranteed.
""")
        print(f"  Next steps:")
        print(f"    1. Install to VMOS:  adb install \"{final_apk}\"")
        print(f"    2. Launch Spotify, login, play music")
        print(f"    3. Switch away, wait 3-5 min, check if still alive")
    print()

if __name__ == "__main__":
    main()
