Param(
  [string]$OutDir = "$(Split-Path -Parent $PSScriptRoot)\out",
  [string]$JavaExe,
  [switch]$Force
)

$ErrorActionPreference = "Stop"

function Select-File([string]$title, [string]$filter = "APK files (*.apk)|*.apk|All files (*.*)|*.*") {
  Add-Type -AssemblyName System.Windows.Forms
  $dialog = New-Object System.Windows.Forms.OpenFileDialog
  $dialog.Title = $title
  $dialog.Filter = $filter
  $dialog.CheckFileExists = $true
  if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
    return $dialog.FileName
  }
  return $null
}

# --- Select java.exe ---
if (-not $JavaExe) {
  Write-Host "Please select java.exe ..."
  $JavaExe = Select-File "Select java.exe" "java.exe (java.exe)|java.exe|All files (*.*)|*.*"
}
if (-not $JavaExe) { throw "No java.exe selected" }

# --- Select Spotify APK ---
Write-Host "Please select Spotify APK..."
$SpotifyApk = Select-File "Select Spotify APK"
if (-not $SpotifyApk) { throw "No Spotify APK selected" }

# --- Select Module APK ---
Write-Host "Please select Module APK..."
$ModuleApk = Select-File "Select Module APK"
if (-not $ModuleApk) { throw "No module APK selected" }

# --- Select NPatch.jar ---
Write-Host "Please select NPatch.jar..."
$NPatchJar = Select-File "Select NPatch.jar" "JAR files (*.jar)|*.jar|All files (*.*)|*.*"
if (-not $NPatchJar) { throw "No NPatch.jar selected" }

Write-Host ""
Write-Host "Java        : $JavaExe"
Write-Host "Spotify APK : $SpotifyApk"
Write-Host "Module APK  : $ModuleApk"
Write-Host "NPatch JAR  : $NPatchJar"
Write-Host "Output Dir  : $OutDir"
Write-Host ""

# --- Prepare output directory ---
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# --- Patch ---
# NPatch's built-in keystore is in BKS (BouncyCastle) format, which is the default on Android.
# On desktop JDK the default keystore type is PKCS12, causing "toDerInputStream rejects tag type 0".
# Fix: NPatchWrapper registers BouncyCastle provider at runtime before calling NPatch.main().
$wrapperDir = $PSScriptRoot
$javaArgs = @("-cp", "$NPatchJar;$wrapperDir", "NPatchWrapper", "-m", $ModuleApk, "-l", "2", "-o", $OutDir)
if ($Force) { $javaArgs += "-f" }
$javaArgs += @($SpotifyApk)

Write-Host "Patching Spotify with SpotiPass module..."
Write-Host ("$JavaExe " + ($javaArgs -join " "))
& $JavaExe @javaArgs

Write-Host "Done. Output directory: $OutDir"
