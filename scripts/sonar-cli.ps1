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
# Nexus path conventions (rename if your layout differs):
#   {base}/sonar-predictor/{version}/sonar-predictor-dist-{version}.zip
#   {base}/temurin/21/windows-{arch}.zip
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

# --- Bundle ---------------------------------------------------------------
$DistDir      = Join-Path $HomeDir "dist\$Version"
$BundleDir    = Join-Path $DistDir 'sonar-predictor'
$BundleMarker = Join-Path $BundleDir 'bin\sonar.bat'

function Ensure-Bundle {
    if ($env:SONAR_PREDICTOR_FORCE -ne '1' -and (Test-Path $BundleMarker)) { return }
    Write-Host "sonar-cli: provisioning sonar-predictor $Version into $DistDir"
    $zip = Join-Path $HomeDir "cache\sonar-predictor-dist-$Version.zip"
    $url = "$NexusBase/sonar-predictor/$Version/sonar-predictor-dist-$Version.zip"
    Fetch $url $zip
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
    $url     = "$NexusBase/temurin/21/$Os-$arch.zip"
    Fetch $url $archive

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
