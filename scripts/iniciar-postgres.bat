@echo off
REM Mercurius PostgreSQL 17 - arranca el servidor en esta ventana.
REM Dejarla abierta mientras se usa la app o se corren pruebas.
set LOGDIR=%LOCALAPPDATA%\PostgreSQL
if not exist "%LOGDIR%" mkdir "%LOGDIR%"
echo ============================================
echo  Mercurius PostgreSQL (puerto 5433)
echo  Cierre esta ventana para detener el servidor
echo ============================================
"C:\pgsql17\bin\pg_ctl.exe" -D "%LOCALAPPDATA%\PostgreSQL\data17" -l "%LOGDIR%\pg17.log" -o "-p 5433" -w start
if errorlevel 1 (
    echo.
    echo El servidor ya estaba corriendo o fallo el arranque. Ver %LOGDIR%\pg17.log
    pause
    exit /b 1
)
echo.
echo Servidor ARRIBA. No cierre esta ventana.
echo Presione Ctrl+C para detenerlo al terminar.
cmd /k
