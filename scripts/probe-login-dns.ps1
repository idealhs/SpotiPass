Param(
  [string[]]$Hosts = @(
    "accounts.spotify.com",
    "challenge.spotify.com",
    "auth-callback.spotify.com",
    "partner-accounts.spotify.com",
    "accounts-gew4.spotify.com",
    "accounts-gue1.spotify.com",
    "accounts-gew1.spotify.com",
    "accounts-guc3.spotify.com",
    "accounts-gae2.spotify.com",
    "www.recaptcha.net",
    "www.gstatic.com"
  ),
  [string[]]$DnsServers = @("223.5.5.5", "119.29.29.29", "8.8.8.8", "1.1.1.1"),
  [int]$TimeoutSec = 6,
  [int]$TopN = 3
)

$ErrorActionPreference = "Stop"

function Resolve-HostIps {
  Param(
    [string]$Hostname,
    [string[]]$Servers
  )

  $all = New-Object System.Collections.Generic.List[object]
  foreach ($dns in $Servers) {
    try {
      $records = Resolve-DnsName -Name $Hostname -Server $dns -Type A -ErrorAction Stop |
        Where-Object { $_.Type -eq "A" -and $_.IPAddress }
      foreach ($r in $records) {
        $all.Add([PSCustomObject]@{
          Host = $Hostname
          DnsServer = $dns
          Ip = $r.IPAddress
        })
      }
    } catch {
      Write-Host "[WARN] DNS resolve failed host=$Hostname server=$dns msg=$($_.Exception.Message)"
    }
  }
  return $all
}

function Test-TlsReachable {
  Param(
    [string]$Hostname,
    [string]$Ip,
    [int]$TimeoutSec
  )

  $resolve = "$Hostname`:`443`:$Ip"
  $url = "https://$Hostname/"
  $args = @(
    "--silent",
    "--show-error",
    "--max-time", "$TimeoutSec",
    "--resolve", $resolve,
    $url,
    "--output", "NUL",
    "--write-out", "%{http_code}"
  )

  try {
    $code = & curl.exe @args 2>$null
    if ($LASTEXITCODE -eq 0 -and $code -match "^\d{3}$" -and $code -ne "000") {
      return [PSCustomObject]@{
        Reachable = $true
        HttpCode = $code
      }
    }
  } catch {
  }

  return [PSCustomObject]@{
    Reachable = $false
    HttpCode = ""
  }
}

if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
  throw "curl.exe not found in PATH"
}

$results = New-Object System.Collections.Generic.List[object]
$hostToIps = @{}

foreach ($targetHost in $Hosts) {
  $hostname = $targetHost
  Write-Host "\n=== Probe $hostname ==="
  $resolved = Resolve-HostIps -Hostname $hostname -Servers $DnsServers
  if (-not $resolved -or $resolved.Count -eq 0) {
    Write-Host "[WARN] No A record resolved: $hostname"
    continue
  }

  $ips = $resolved | Select-Object -ExpandProperty Ip -Unique
  $hostToIps[$hostname] = $ips
  Write-Host "Candidate IP count: $($ips.Count)"

  foreach ($ip in $ips) {
    $test = Test-TlsReachable -Hostname $hostname -Ip $ip -TimeoutSec $TimeoutSec
    if ($test.Reachable) {
      Write-Host "[OK] $hostname -> $ip (HTTP $($test.HttpCode))"
      $results.Add([PSCustomObject]@{
        Host = $hostname
        Ip = $ip
        HttpCode = $test.HttpCode
      })
    } else {
      Write-Host "[FAIL] $hostname -> $ip"
    }
  }
}

Write-Host "\n===== Reachable IP Summary ====="
if ($results.Count -eq 0) {
  Write-Host "No reachable IP detected."
  exit 1
}

$results | Sort-Object Host, Ip | Format-Table -AutoSize

Write-Host "\n===== SpotiPass DNS Rules (copy into UI) ====="
foreach ($targetHost in $Hosts) {
  $hostname = $targetHost
  $okIps = $results |
    Where-Object { $_.Host -eq $hostname } |
    Select-Object -ExpandProperty Ip -Unique |
    Select-Object -First $TopN

  if ($okIps -and $okIps.Count -gt 0) {
    Write-Host ("{0}={1}" -f $hostname, ($okIps -join ","))
  }
}
