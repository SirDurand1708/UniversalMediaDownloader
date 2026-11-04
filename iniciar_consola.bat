@echo off
title OmniVault Desktop (Modo Depuracion con Consola)
cd /d "%~dp0"

echo ==============================================================
echo  Iniciando OmniVault Desktop en Modo Depuracion...
echo  (Esta ventana mostrara los registros en tiempo real)
echo ==============================================================
echo.

where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    py -3 app.py
    goto FIN
)

where python >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    python app.py
    goto FIN
)

echo ERROR: No se encontro Python en el sistema.
pause
exit /b 1

:FIN
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Ocurrio un error durante la ejecucion de la aplicacion.
    pause
)
