# sonar-cli.ps1 — bootstrap wrapper for the sonar-predictor distribution (Windows).
#
# Mirror of scripts/sonar-cli.sh for PowerShell 5.1+ / 7+.
#
# Responsibilities (the bundle's own bin\sonar.bat does NOT do these):
#   1. Fetch the dist zip from a corporate Nexus raw repo and extract it into
#      $env:USERPROFILE\.sonar-predictor\dist\<version>\sonar-predictor\.
#   2. Ensure a Java 21+ runtime is available. If JAVA_HOME or PATH already
#      has one, use it. Otherwise fetch a Temurin JDK 21 zip from the same
#      Nexus and cache it under .sonar-predictor\jdk\21\windows-x64\.
#   3. Hand off to the bundle's bin\sonar.bat, which spawns the daemon child.
#
# Required env:
#   SONAR_NEXUS_BASE          Nexus raw repo base URL (no trailing slash)
#
# Optional env:
#   SONAR_PREDICTOR_VERSION   Default: 0.3.0-SNAPSHOT
#   SONAR_PREDICTOR_HOME      Default: $env:USERPROFILE\.sonar-predictor
#   SONAR_PREDICTOR_FORCE     "1" to re-download even if cached
#
# Integrity verification (fail-closed by default):
#   Every artifact (dist zip, JDK zip) is SHA-256-verified against a pinned
#   expected value BEFORE it is extracted or moved into place. This mirrors the
#   download->verify->promote contract the Java Downloader enforces, and matters
#   most for the JDK: it is the trust root that runs every downstream in-JVM
#   check, so an unverified JDK makes those checks moot.
#
#   SONAR_DIST_SHA256         Expected lowercase-hex SHA-256 of the dist zip.
#   SONAR_JDK_SHA256          Expected lowercase-hex SHA-256 of the JDK zip.
#   If unset, the expected hash is read from a sibling checksum file at the SAME
#   base URL the artifact came from (SHA256SUMS for the dist; <zip>.sha256.txt
#   for Adoptium JDKs).
#   SONAR_ALLOW_UNVERIFIED    "1" to proceed when NO expected hash can be
#                             obtained. Prints a loud warning. Default (unset)
#                             refuses — no unverified bytes are extracted.
#
# Nexus path conventions (rename if your layout differs):
#   {base}/sonar-predictor/{version}/sonar-predictor-dist-{version}.zip
#   {base}/sonar-predictor/{version}/SHA256SUMS  (sibling; lists dist-{version}.zip)
#   {base}/temurin/21/windows-{arch}.zip
#   {base}/temurin/21/windows-{arch}.zip.sha256.txt  (Adoptium sibling checksum)
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\sonar-cli.ps1 -- <sonar args>

[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Args
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

function Die([string]$msg) {
    Write-Error "sonar-cli: $msg"
    exit 2
}

# --- Config ----------------------------------------------------------------
$Version  = if ($env:SONAR_PREDICTOR_VERSION) { $env:SONAR_PREDICTOR_VERSION } else { '0.3.0-SNAPSHOT' }
$HomeDir  = if ($env:SONAR_PREDICTOR_HOME)    { $env:SONAR_PREDICTOR_HOME }    else { Join-Path $env:USERPROFILE '.sonar-predictor' }
$MinMajor = 21

if (-not $env:SONAR_NEXUS_BASE) {
    Die 'SONAR_NEXUS_BASE is required (Nexus raw repo base URL, no trailing slash).'
}
$NexusBase = $env:SONAR_NEXUS_BASE.TrimEnd('/')

# --- Arch detection --------------------------------------------------------
# PowerShell on Windows: PROCESSOR_ARCHITECTURE = AMD64 / ARM64 / x86.
$arch = switch ($env:PROCESSOR_ARCHITECTURE) {
    'AMD64' { 'x64' }
    'ARM64' { 'aarch64' }
    default { Die "unsupported PROCESSOR_ARCHITECTURE: $($env:PROCESSOR_ARCHITECTURE)." }
}
$Os = 'windows'

# --- Download helper -------------------------------------------------------
function Fetch([string]$Url, [string]$Target) {
    $dir = Split-Path -Parent $Target
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $tmp = "$Target.partial"
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    Write-Host "  fetching $Url"
    try {
        Invoke-WebRequest -Uri $Url -OutFile $tmp -UseBasicParsing -ErrorAction Stop
    } catch {
        if (Test-Path $tmp) { Remove-Item $tmp -Force }
        Die "download failed: $Url ($_)"
    }
    if (Test-Path $Target) { Remove-Item $Target -Force }
    Move-Item $tmp $Target
}

# Fetch-Optional — like Fetch, but returns $false instead of dying when the URL
# is absent (used for sibling checksum files, which may not exist on every
# mirror). Leaves no partial behind on failure.
function Fetch-Optional([string]$Url, [string]$Target) {
    $dir = Split-Path -Parent $Target
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $tmp = "$Target.partial"
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    try {
        Invoke-WebRequest -Uri $Url -OutFile $tmp -UseBasicParsing -ErrorAction Stop
    } catch {
        if (Test-Path $tmp) { Remove-Item $tmp -Force }
        return $false
    }
    if (Test-Path $Target) { Remove-Item $Target -Force }
    Move-Item $tmp $Target
    return $true
}

# --- Integrity verification ------------------------------------------------
# Get-Sha256 — lowercase-hex SHA-256 of a file via Get-FileHash (built in).
function Get-Sha256([string]$File) {
    return (Get-FileHash -Path $File -Algorithm SHA256).Hash.ToLowerInvariant()
}

# Get-ChecksumForArtifact — extracts the expected hash for $Name from a
# checksum file. Handles both the multi-line GNU "<hash>  <name>" SHA256SUMS
# format and a single bare/"<hash>  <name>" line (Adoptium .sha256.txt). Returns
# the lowercase hash, or $null if not found.
function Get-ChecksumForArtifact([string]$SumsFile, [string]$Name) {
    $lines = Get-Content -Path $SumsFile -ErrorAction SilentlyContinue
    if (-not $lines) { return $null }
    foreach ($line in $lines) {
        if ($line -match "[\s\*]$([regex]::Escape($Name))\s*$") {
            return ($line -split '\s+')[0].ToLowerInvariant()
        }
    }
    # Single-entry file: first line that begins with a 64-hex-char hash.
    foreach ($line in $lines) {
        if ($line -match '^\s*([0-9a-fA-F]{64})(\s|$)') {
            return $Matches[1].ToLowerInvariant()
        }
    }
    return $null
}

# Resolve-ExpectedSha — resolves the expected SHA-256 in priority order:
#   1. $Override (explicit env value), if non-empty;
#   2. the sibling checksum file fetched from $SumsUrl.
# Returns the lowercase hash, or $null if none could be obtained.
function Resolve-ExpectedSha([string]$Override, [string]$Name, [string]$SumsUrl, [string]$CacheDir) {
    if ($Override) { return $Override.Trim().ToLowerInvariant() }
    $sumsFile = Join-Path $CacheDir ([System.IO.Path]::GetFileName($SumsUrl))
    if (Fetch-Optional $SumsUrl $sumsFile) {
        return (Get-ChecksumForArtifact $sumsFile $Name)
    }
    return $null
}

# Verify-Artifact — verifies $File's SHA-256 against the resolved expected
# value BEFORE any extract/move. On mismatch: deletes $File and dies non-zero,
# naming expected vs actual. If no expected hash is obtainable: dies unless
# SONAR_ALLOW_UNVERIFIED=1, in which case it prints a loud warning and returns.
function Verify-Artifact([string]$File, [string]$Name, [string]$Override, [string]$SumsUrl) {
    $cache = Split-Path -Parent $File
    $expected = Resolve-ExpectedSha $Override $Name $SumsUrl $cache
    if (-not $expected) {
        if ($env:SONAR_ALLOW_UNVERIFIED -eq '1') {
            Write-Warning "sonar-cli: no SHA-256 available for $Name; proceeding UNVERIFIED"
            Write-Warning "sonar-cli: SONAR_ALLOW_UNVERIFIED=1 bypasses integrity checks — this is NOT recommended."
            return
        }
        if (Test-Path $File) { Remove-Item $File -Force }
        Die "no expected SHA-256 for $Name (set SONAR_DIST_SHA256/SONAR_JDK_SHA256, publish a sibling checksum file, or set SONAR_ALLOW_UNVERIFIED=1 to override). Refusing to extract unverified bytes."
    }
    $actual = Get-Sha256 $File
    if ($actual -ne $expected) {
        if (Test-Path $File) { Remove-Item $File -Force }
        Die "SHA-256 mismatch for ${Name}: expected $expected but got $actual. Refusing to extract."
    }
    Write-Host "  verified $Name (sha256 $expected)"
}

# --- Bundle ---------------------------------------------------------------
$DistDir      = Join-Path $HomeDir "dist\$Version"
$BundleDir    = Join-Path $DistDir 'sonar-predictor'
$BundleMarker = Join-Path $BundleDir 'bin\sonar.bat'

function Ensure-Bundle {
    if ($env:SONAR_PREDICTOR_FORCE -ne '1' -and (Test-Path $BundleMarker)) { return }
    Write-Host "sonar-cli: provisioning sonar-predictor $Version into $DistDir"
    $zip  = Join-Path $HomeDir "cache\sonar-predictor-dist-$Version.zip"
    $name = "sonar-predictor-dist-$Version.zip"
    $url  = "$NexusBase/sonar-predictor/$Version/$name"
    $sumsUrl = "$NexusBase/sonar-predictor/$Version/SHA256SUMS"
    Fetch $url $zip
    # Verify integrity BEFORE extracting or touching the install location.
    Verify-Artifact $zip $name $env:SONAR_DIST_SHA256 $sumsUrl
    if (Test-Path $DistDir) { Remove-Item $DistDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
    try {
        Expand-Archive -Path $zip -DestinationPath $DistDir -Force -ErrorAction Stop
    } catch {
        Die "could not extract ${zip}: $_"
    }
    if (-not (Test-Path $BundleMarker)) { Die "bundle missing bin\sonar.bat after extract: $BundleDir" }
}

# --- Java 21+ detection ----------------------------------------------------
function Get-JavaMajor([string]$Java) {
    if (-not (Test-Path $Java)) { return $null }
    try {
        $out = & $Java -version 2>&1 | Out-String
    } catch { return $null }
    $line = ($out -split "`r?`n")[0]
    if ($line -match 'version "([^"]+)"') {
        $v = $Matches[1]
        if ($v -like '1.*') { return [int]($v.Split('.')[1]) }   # legacy 1.8 -> 8
        return [int]($v.Split('.')[0])                            # 17.0.10  -> 17
    }
    return $null
}

function Test-JavaMinPlus([string]$Java) {
    $m = Get-JavaMajor $Java
    return ($null -ne $m -and $m -ge $MinMajor)
}

function Find-SystemJava {
    if ($env:JAVA_HOME) {
        $j = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-JavaMinPlus $j) { return $j }
    }
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd -and (Test-JavaMinPlus $cmd.Source)) { return $cmd.Source }

    # Common Windows install roots.
    $roots = @(
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Java",
        "${env:ProgramFiles(x86)}\Java",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        Get-ChildItem -Directory -Path $root -ErrorAction SilentlyContinue | ForEach-Object {
            $j = Join-Path $_.FullName 'bin\java.exe'
            if (Test-JavaMinPlus $j) { return $j }
        }
    }
    return $null
}

# --- JDK provisioning (only if no system Java 21+) -------------------------
$JdkDir    = Join-Path $HomeDir "jdk\21\$Os-$arch"
$JdkMarker = Join-Path $JdkDir 'bin\java.exe'

function Ensure-CachedJdk {
    if ((Test-Path $JdkMarker) -and (Test-JavaMinPlus $JdkMarker)) { return }
    Write-Host "sonar-cli: provisioning Temurin JDK 21 ($Os-$arch) into $JdkDir"
    $archive = Join-Path $HomeDir "cache\temurin-21-$Os-$arch.zip"
    $name    = "$Os-$arch.zip"
    $url     = "$NexusBase/temurin/21/$name"
    $sumsUrl = "$url.sha256.txt"
    Fetch $url $archive
    # Verify integrity BEFORE extracting — the JDK is the trust root that runs
    # every downstream in-JVM SHA-256 check, so this gate is the critical one.
    Verify-Artifact $archive $name $env:SONAR_JDK_SHA256 $sumsUrl

    $stage = Join-Path $HomeDir "cache\jdk-stage-$PID"
    if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    try {
        Expand-Archive -Path $archive -DestinationPath $stage -Force -ErrorAction Stop
    } catch {
        Die "could not extract ${archive}: $_"
    }

    # Temurin Windows zips nest one level (jdk-21.0.5+11/).
    $root = Get-ChildItem -Directory $stage | Select-Object -First 1
    if (-not $root) { Die "JDK archive appears empty: $archive" }
    $jdkHome = $root.FullName
    if (-not (Test-Path (Join-Path $jdkHome 'bin\java.exe'))) {
        Die "JDK archive missing bin\java.exe: $archive"
    }

    if (Test-Path $JdkDir) { Remove-Item $JdkDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $JdkDir) | Out-Null
    Move-Item $jdkHome $JdkDir
    Remove-Item $stage -Recurse -Force

    if (-not (Test-JavaMinPlus $JdkMarker)) { Die "cached JDK is not version $MinMajor+: $JdkMarker" }
}

# --- Orchestrate -----------------------------------------------------------
Ensure-Bundle

$java = Find-SystemJava
if (-not $java) {
    Ensure-CachedJdk
    $env:JAVA_HOME = $JdkDir
    # bundle's bin\sonar.bat discovers Java via JAVA_HOME / PATH; setting
    # JAVA_HOME is the lowest-friction handoff.
}

# --- Hand off --------------------------------------------------------------
$cmdLine = @($BundleMarker) + ($Args | ForEach-Object { $_ })
& cmd.exe /c $cmdLine
exit $LASTEXITCODE
