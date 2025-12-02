Param(
  [string]$OutDir = "$(Split-Path -Parent $PSScriptRoot)\out",
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
Write-Host "Spotify APK : $SpotifyApk"
Write-Host "Module APK  : $ModuleApk"
Write-Host "NPatch JAR  : $NPatchJar"
Write-Host "Output Dir  : $OutDir"
Write-Host ""

# --- Prepare output directory ---
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# --- Patch ---
$javaArgs = @("-jar", $NPatchJar, "-m", $ModuleApk, "-l", "2", "-o", $OutDir)
if ($Force) { $javaArgs += "-f" }
$javaArgs += @($SpotifyApk)

Write-Host "Patching Spotify with SpotiPass module..."
Write-Host ("java " + ($javaArgs -join " "))
& java @javaArgs

Write-Host "Done. Output directory: $OutDir"
