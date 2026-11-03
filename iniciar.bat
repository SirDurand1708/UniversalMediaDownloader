@echo off
title OmniVault Desktop - Universal Media Hub
cd /d "%~dp0"

:: Intentar con el lanzador oficial de Python (py)
where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    py -3 app.py
    goto FIN
)

:: Intentar con el ejecutable directo 'python'
where python >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    python app.py
    goto FIN
)

:: Si ninguno funciona, mostrar mensaje explicativo
echo ==============================================================
echo  ERROR: No se encontro Python instalado en el sistema.
echo ==============================================================
echo  Para ejecutar OmniVault Desktop:
echo  1. Descarga Python desde https://www.python.org/downloads/
echo  2. Durante la instalacion, asegurate de marcar:
echo     "Add Python to PATH" (Agregar Python al PATH).
echo ==============================================================
echo.
pause
exit /b 1

:FIN
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Ocurrio un error durante la ejecucion de la aplicacion.
    pause
)
