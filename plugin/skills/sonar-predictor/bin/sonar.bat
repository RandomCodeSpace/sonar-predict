@echo off
rem Bootstrap wrapper for the sonar-predictor skill bundle (Windows).
rem
rem Loads ../config.env (KEY=VALUE lines, # for comments) and uses the
rem configured Maven proxy + bundle version to download the analyzer bundle
rem on first invocation. Env vars of the same name take precedence over the
rem file.
rem
rem Java auto-install is NOT yet supported on Windows — install Java 17+
rem manually (or set JAVA_HOME). The bundle's own launcher will discover it.
rem
rem Override the bundle location for air-gapped / pre-staged installs:
rem   set SONAR_PREDICTOR_HOME=C:\path\to\extracted\sonar-predictor

setlocal enabledelayedexpansion

rem ---- 1. load config.env (env vars already set win) ----
set "CONFIG=%~dp0..\config.env"
if exist "%CONFIG%" (
  for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%CONFIG%") do (
    if not "%%a"=="" if not defined %%a set "%%a=%%b"
  )
)

rem ---- defaults ----
if not defined SONAR_MAVEN_REPO_URL set "SONAR_MAVEN_REPO_URL=https://repo1.maven.org/maven2"
if not defined SONAR_BUNDLE_VERSION set "SONAR_BUNDLE_VERSION=0.1.1"

set "VERSION=%SONAR_BUNDLE_VERSION%"
set "ARTIFACT=sonar-predictor-dist"
set "BUNDLE_URL_BASE=%SONAR_MAVEN_REPO_URL%/io/github/randomcodespace/sonarpredict/sonar-predictor-dist/%VERSION%"

if defined SONAR_PREDICTOR_HOME (
  set "SKILL_DIR=%SONAR_PREDICTOR_HOME%"
) else (
  if defined LOCALAPPDATA (
    set "SKILL_DIR=%LOCALAPPDATA%\sonar-predictor\%VERSION%"
  ) else (
    set "SKILL_DIR=%USERPROFILE%\.cache\sonar-predictor\%VERSION%"
  )
)

set "REAL_SONAR=%SKILL_DIR%\bin\sonar.bat"

if exist "%REAL_SONAR%" goto :exec

echo sonar-predictor: first run -- downloading %VERSION% bundle from %SONAR_MAVEN_REPO_URL%... 1>&2

set "TMP=%TEMP%\sonar-predictor-bootstrap-%RANDOM%-%RANDOM%"
mkdir "%TMP%" || (echo sonar-predictor: failed to create temp dir 1>&2 & exit /b 2)

set "ZIP=%TMP%\bundle.zip"
set "SHA=%TMP%\bundle.zip.sha1"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ProgressPreference='SilentlyContinue'; try { Invoke-WebRequest -Uri '%BUNDLE_URL_BASE%/%ARTIFACT%-%VERSION%.zip' -OutFile '%ZIP%' } catch { exit 2 }"
if errorlevel 1 (echo sonar-predictor: bundle download failed 1>&2 & rmdir /S /Q "%TMP%" & exit /b 2)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ProgressPreference='SilentlyContinue'; try { Invoke-WebRequest -Uri '%BUNDLE_URL_BASE%/%ARTIFACT%-%VERSION%.zip.sha1' -OutFile '%SHA%' } catch { exit 2 }"
if errorlevel 1 (echo sonar-predictor: SHA-1 download failed 1>&2 & rmdir /S /Q "%TMP%" & exit /b 2)

for /f "tokens=1" %%i in (%SHA%) do set "EXPECTED=%%i"
for /f "tokens=1" %%i in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA1 '%ZIP%').Hash.ToLower()"') do set "ACTUAL=%%i"
if /i not "!EXPECTED!"=="!ACTUAL!" (
  echo sonar-predictor: SHA-1 verification failed ^(expected !EXPECTED!, got !ACTUAL!^) 1>&2
  rmdir /S /Q "%TMP%"
  exit /b 2
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ProgressPreference='SilentlyContinue'; try { Expand-Archive -Force -Path '%ZIP%' -DestinationPath '%TMP%\extracted' } catch { exit 2 }"
if errorlevel 1 (echo sonar-predictor: extraction failed 1>&2 & rmdir /S /Q "%TMP%" & exit /b 2)

if not exist "%TMP%\extracted\sonar-predictor" (
  echo sonar-predictor: unexpected bundle layout ^(no sonar-predictor\ at zip root^) 1>&2
  rmdir /S /Q "%TMP%"
  exit /b 2
)

for %%I in ("%SKILL_DIR%") do set "PARENT=%%~dpI"
if not exist "%PARENT%" mkdir "%PARENT%"
move /Y "%TMP%\extracted\sonar-predictor" "%SKILL_DIR%" >nul
rmdir /S /Q "%TMP%"

if not exist "%REAL_SONAR%" (
  echo sonar-predictor: bootstrap completed but %REAL_SONAR% is missing 1>&2
  exit /b 2
)

:exec
if /i "%~1"=="agent-scan" (
  shift /1
  call :agent_scan %*
  exit /b %ERRORLEVEL%
)
call "%REAL_SONAR%" %*
exit /b %ERRORLEVEL%

rem agent-scan: write JSON to .sonar-predictor\scan.json, gitignore that
rem dir on first use, print a short pointer on stdout.
:agent_scan
set "SCAN_FILE=.sonar-predictor\scan.json"
if not exist ".sonar-predictor" mkdir ".sonar-predictor"

git rev-parse --git-dir >nul 2>&1
if not errorlevel 1 (
  if not exist .gitignore (
    >>.gitignore echo .sonar-predictor/
  ) else (
    findstr /C:".sonar-predictor/" .gitignore >nul 2>&1 || >>.gitignore echo .sonar-predictor/
  )
)

if "%~1"=="" (
  call "%REAL_SONAR%" --format json check --diff > "%SCAN_FILE%" 2>&1
) else (
  call "%REAL_SONAR%" --format json %* > "%SCAN_FILE%" 2>&1
)
set RC=%ERRORLEVEL%

echo sonar-predictor: scan complete -^> %SCAN_FILE%
echo   query: jq ^"...^" %SCAN_FILE%
exit /b %RC%
