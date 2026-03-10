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
  [string[]]$RecordTypes = @("A", "AAAA"),
  [int]$TimeoutSec = 6,
  [int]$TopN = 3
)

$ErrorActionPreference = "Stop"

function Resolve-HostIps {
  Param(
    [string]$Hostname,
    [string[]]$Servers,
    [string[]]$RecordTypes
  )

  $all = New-Object System.Collections.Generic.List[object]
  foreach ($dns in $Servers) {
    foreach ($recordType in $RecordTypes) {
      try {
        $records = Resolve-DnsName -Name $Hostname -Server $dns -Type $recordType -ErrorAction Stop |
          Where-Object { $_.Type -eq $recordType -and $_.IPAddress }
        foreach ($r in $records) {
          $all.Add([PSCustomObject]@{
            Host = $Hostname
            DnsServer = $dns
            RecordType = $recordType
            AddressFamily = if ($recordType -eq "AAAA") { "IPv6" } else { "IPv4" }
            Ip = $r.IPAddress
          })
        }
      } catch {
        Write-Host "[WARN] DNS resolve failed host=$Hostname type=$recordType server=$dns msg=$($_.Exception.Message)"
      }
    }
  }
  return $all
}

function Format-ResolveTarget {
  Param(
    [string]$Ip,
    [string]$RecordType
  )

  if ($RecordType -eq "AAAA" -or $Ip.Contains(":")) {
    return "[$Ip]"
  }
  return $Ip
}

function Test-TlsReachable {
  Param(
    [string]$Hostname,
    [string]$Ip,
    [string]$RecordType,
    [int]$TimeoutSec
  )

  $resolve = "$Hostname`:`443`:$(Format-ResolveTarget -Ip $Ip -RecordType $RecordType)"
  $url = "https://$Hostname/"
  $args = @(
    "--silent",
    "--show-error",
    "--noproxy", "*",
    "--max-time", "$TimeoutSec",
    "--resolve", $resolve,
    $url,
    "--output", "NUL",
    "--write-out", "%{http_code}"
  )
  if ($RecordType -eq "AAAA") {
    $args += "--ipv6"
  } else {
    $args += "--ipv4"
  }

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

$normalizedRecordTypes = @(
  $RecordTypes |
    ForEach-Object { if ($null -eq $_) { "" } else { $_.Trim().ToUpperInvariant() } } |
    Where-Object { $_ -in @("A", "AAAA") } |
    Select-Object -Unique
)
if (-not $normalizedRecordTypes -or $normalizedRecordTypes.Count -eq 0) {
  throw "No valid record types specified. Supported values: A, AAAA"
}

$RecordTypes = $normalizedRecordTypes

$allResolved = New-Object System.Collections.Generic.List[object]
$results = New-Object System.Collections.Generic.List[object]

foreach ($targetHost in $Hosts) {
  $hostname = $targetHost
  Write-Host "\n=== Probe $hostname ==="
  $resolved = Resolve-HostIps -Hostname $hostname -Servers $DnsServers -RecordTypes $RecordTypes
  if (-not $resolved -or $resolved.Count -eq 0) {
    Write-Host "[WARN] No DNS record resolved: $hostname"
    continue
  }

  $resolvedUnique = $resolved |
    Sort-Object RecordType, Ip |
    Group-Object RecordType, Ip |
    ForEach-Object { $_.Group[0] }

  foreach ($item in $resolvedUnique) {
    $allResolved.Add($item)
  }

  foreach ($group in ($resolvedUnique | Group-Object RecordType)) {
    Write-Host "Candidate $($group.Name) count: $($group.Count)"
  }

  foreach ($entry in $resolvedUnique) {
    $test = Test-TlsReachable -Hostname $hostname -Ip $entry.Ip -RecordType $entry.RecordType -TimeoutSec $TimeoutSec
    if ($test.Reachable) {
      Write-Host "[OK] $hostname [$($entry.RecordType)] -> $($entry.Ip) (HTTP $($test.HttpCode))"
      $results.Add([PSCustomObject]@{
        Host = $hostname
        RecordType = $entry.RecordType
        AddressFamily = $entry.AddressFamily
        Ip = $entry.Ip
        HttpCode = $test.HttpCode
      })
    } else {
      Write-Host "[FAIL] $hostname [$($entry.RecordType)] -> $($entry.Ip)"
    }
  }
}

Write-Host "\n===== Resolved Record Summary ====="
if ($allResolved.Count -eq 0) {
  Write-Host "No DNS record resolved."
} else {
  $allResolved | Sort-Object Host, RecordType, Ip | Format-Table Host, RecordType, AddressFamily, Ip -AutoSize
}

Write-Host "\n===== AAAA Presence Summary ====="
$aaaaResolved = $allResolved | Where-Object { $_.RecordType -eq "AAAA" }
if (-not $aaaaResolved -or $aaaaResolved.Count -eq 0) {
  Write-Host "No AAAA record resolved."
} else {
  foreach ($targetHost in $Hosts) {
    $hostname = $targetHost
    $aaaaIps = $aaaaResolved |
      Where-Object { $_.Host -eq $hostname } |
      Select-Object -ExpandProperty Ip -Unique
    if ($aaaaIps -and $aaaaIps.Count -gt 0) {
      Write-Host ("{0}={1}" -f $hostname, ($aaaaIps -join ","))
    }
  }
}

Write-Host "\n===== Reachable IP Summary ====="
if ($results.Count -eq 0) {
  Write-Host "No reachable IP detected."
} else {
  $results | Sort-Object Host, RecordType, Ip | Format-Table Host, RecordType, AddressFamily, Ip, HttpCode -AutoSize
}

Write-Host "\n===== SpotiPass DNS Rules (IPv4 only, copy into UI) ====="
Write-Host "[INFO] IPv6 probe result is shown above, but current SpotiPass login DNS parser only accepts IPv4 literals."
foreach ($targetHost in $Hosts) {
  $hostname = $targetHost
  $okIps = $results |
    Where-Object { $_.Host -eq $hostname -and $_.RecordType -eq "A" } |
    Select-Object -ExpandProperty Ip -Unique |
    Select-Object -First $TopN

  if ($okIps -and $okIps.Count -gt 0) {
    Write-Host ("{0}={1}" -f $hostname, ($okIps -join ","))
  }
}

if ($results.Count -eq 0) {
  exit 1
}
