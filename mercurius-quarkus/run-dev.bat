@echo off
setlocal EnableExtensions

rem ============================================================================
rem  Mercurius Quarkus - Development Mode (interactive, foreground)
rem ============================================================================
rem  Runs `mvn quarkus:dev` in the FOREGROUND so you get the live-coding
rem  interactive console (press `r` to restart, `o` to toggle test output,
rem  `e` to edit command-line args, Ctrl+C to stop).
rem
rem  Application: http://localhost:8081/Mercurius
rem  Debug port:  5005  (remote debugger may attach here)
rem ============================================================================

echo Starting Mercurius Quarkus in Development Mode...
echo.
echo Application will be available at: http://localhost:8081/Mercurius
echo Debug port: 5005
echo.
echo Tip: keep this window open. Quarkus hot-reloads on save.
echo     Press Ctrl+C to stop the server.
echo.

rem Clean before every launch so stale classes are never picked up, then run
rem Quarkus dev mode (which recompiles and hot-reloads on subsequent saves).

rem If a previous dev run was killed abruptly, the web-bundler build pipeline
rem (deno.exe / esbuild.exe) can survive as an orphan and hold a lock on
rem target\web-bundler\dev, which makes `mvn clean` fail ("Failed to delete
rem ...web-bundler\dev"). Kill only stale processes whose command line points
rem into THIS project's target\web-bundler, so unrelated deno/esbuild jobs are
rem never touched.
powershell -NoProfile -Command ^
  "Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('deno.exe','esbuild.exe') -and $_.CommandLine -like '*mercurius-quarkus\target\web-bundler*' } | ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop } catch {} }"

call mvn clean quarkus:dev -Dquarkus.jvm.args="-Xms1g -Xmx3g -XX:ReservedCodeCacheSize=512m -XX:+UseG1GC -XX:+UseStringDeduplication"
set "MVN_EXIT=%ERRORLEVEL%"

echo.
if "%MVN_EXIT%"=="0" (
    echo Dev server stopped cleanly.
) else (
    echo Dev server exited with error code %MVN_EXIT%.
    echo Check the output above for details.
)

echo.
pause
endlocal
exit /b %MVN_EXIT%
