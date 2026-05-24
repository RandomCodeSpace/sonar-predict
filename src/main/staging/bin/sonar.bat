@echo off
setlocal EnableDelayedExpansion
rem sonar-predictor launcher (Windows) — self-contained distribution entry point.
rem
rem Auto-discovers a Java 21+ runtime so no JAVA_HOME / PATH setup is required,
rem then runs the bundled CLI with the bundle's jar/plugin locations passed in.
rem
rem TODO(windows): the daemon's IPC uses a Unix domain socket path; full
rem Windows support needs a named-pipe / AF_UNIX-on-Windows transport in the
rem protocol + daemon modules. This launcher is provided best-effort so the
rem distribution bundle is shape-complete; daemon-backed analysis on Windows is not
rem yet verified.

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "BUNDLE_DIR=%%~fI"

rem The shaded jars carry a version suffix (sonar-predictor-cli-X.Y.Z.jar).
rem Glob and pick the first match so the launcher stays version-agnostic.
set "CLI_JAR="
for %%F in ("%BUNDLE_DIR%\lib\sonar-predictor-cli-*.jar") do (
    if not defined CLI_JAR set "CLI_JAR=%%~fF"
)
set "DAEMON_JAR="
for %%F in ("%BUNDLE_DIR%\lib\sonar-predictor-daemon-*.jar") do (
    if not defined DAEMON_JAR set "DAEMON_JAR=%%~fF"
)
set "PLUGINS_DIR=%BUNDLE_DIR%\plugins"

if not defined CLI_JAR (
    echo sonar-predictor: no sonar-predictor-cli-*.jar found in %BUNDLE_DIR%\lib - the distribution bundle is incomplete. 1>&2
    exit /b 2
)
if not exist "%CLI_JAR%" (
    echo sonar-predictor: missing %CLI_JAR% - the distribution bundle is incomplete. 1>&2
    exit /b 2
)

set "JAVA="

rem 1. %JAVA_HOME%\bin\java.exe
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        call :checkJava "%JAVA_HOME%\bin\java.exe"
        if not errorlevel 1 set "JAVA=%JAVA_HOME%\bin\java.exe"
    )
)

rem 2. java on PATH
if not defined JAVA (
    for %%J in (java.exe) do set "PATH_JAVA=%%~$PATH:J"
    if defined PATH_JAVA (
        call :checkJava "!PATH_JAVA!"
        if not errorlevel 1 set "JAVA=!PATH_JAVA!"
    )
)

rem 3. common install locations
if not defined JAVA (
    for %%D in (
        "%ProgramFiles%\Java"
        "%ProgramFiles%\Eclipse Adoptium"
        "%ProgramFiles%\Microsoft\jdk"
        "%ProgramFiles(x86)%\Java"
    ) do (
        if exist "%%~D" (
            for /d %%V in ("%%~D\*") do (
                if not defined JAVA (
                    if exist "%%~V\bin\java.exe" (
                        call :checkJava "%%~V\bin\java.exe"
                        if not errorlevel 1 set "JAVA=%%~V\bin\java.exe"
                    )
                )
            )
        )
    )
)

if not defined JAVA (
    echo sonar-predictor needs Java 21+; none found. 1>&2
    echo Checked: %%JAVA_HOME%%, PATH, and common install locations. 1>&2
    echo Install a JDK/JRE 21 or newer, or set JAVA_HOME to one. 1>&2
    exit /b 2
)

"%JAVA%" "-Dsonar.java.exe=%JAVA%" "-Dsonar.daemon.jar=%DAEMON_JAR%" "-Dsonar.plugins.dir=%PLUGINS_DIR%" -jar "%CLI_JAR%" %*
exit /b %ERRORLEVEL%

rem --- :checkJava <java.exe>  -> errorlevel 0 if major version >= 21 ----------
:checkJava
set "_JV="
for /f "tokens=3" %%v in ('"%~1" -version 2^>^&1 ^| findstr /i "version"') do (
    if not defined _JV set "_JV=%%~v"
)
if not defined _JV exit /b 1
set "_MAJOR="
for /f "tokens=1,2 delims=." %%a in ("%_JV%") do (
    if "%%a"=="1" ( set "_MAJOR=%%b" ) else ( set "_MAJOR=%%a" )
)
if not defined _MAJOR exit /b 1
if %_MAJOR% GEQ 21 ( exit /b 0 ) else ( exit /b 1 )
