@echo off
rem Bootstrap wrapper for the sonar-predictor skill bundle (Windows).
rem
rem On first invocation, downloads the analyzer bundle (~150 MB) from Maven
rem Central into the user cache, verifies its SHA-1, and unpacks it. Every
rem subsequent call dispatches to the cached launcher directly — no network.
rem
rem Override the bundle location for air-gapped or pre-staged installs:
rem   set SONAR_PREDICTOR_HOME=C:\path\to\extracted\sonar-predictor

setlocal enabledelayedexpansion

rem v0.1.2's dist artifact on Central is the (now-removed) plugin-bundle shape,
rem not the skill-bundle this wrapper expects. v0.1.1 is the last good skill
rem bundle; v0.1.3 onwards will be skill-shaped again.
set "VERSION=0.1.1"
set "BASE_URL=https://repo1.maven.org/maven2/io/github/randomcodespace/sonarpredict/sonar-predictor-dist/%VERSION%"

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

echo sonar-predictor: first run -- downloading %VERSION% bundle from Maven Central... 1>&2

set "TMP=%TEMP%\sonar-predictor-bootstrap-%RANDOM%-%RANDOM%"
mkdir "%TMP%" || (echo sonar-predictor: failed to create temp dir 1>&2 & exit /b 2)

set "ZIP=%TMP%\bundle.zip"
set "SHA=%TMP%\bundle.zip.sha1"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ProgressPreference='SilentlyContinue'; try { Invoke-WebRequest -Uri '%BASE_URL%/sonar-predictor-dist-%VERSION%.zip' -OutFile '%ZIP%' } catch { exit 2 }"
if errorlevel 1 (echo sonar-predictor: bundle download failed 1>&2 & rmdir /S /Q "%TMP%" & exit /b 2)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ProgressPreference='SilentlyContinue'; try { Invoke-WebRequest -Uri '%BASE_URL%/sonar-predictor-dist-%VERSION%.zip.sha1' -OutFile '%SHA%' } catch { exit 2 }"
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
call "%REAL_SONAR%" %*
exit /b %ERRORLEVEL%
