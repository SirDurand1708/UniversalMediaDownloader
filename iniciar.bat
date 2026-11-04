@echo off
title OmniVault Desktop - Universal Media Hub
cd /d "%~dp0"

:: 1. Intentar lanzar sin ventana de consola mediante pyw (Python Windowless)
where pyw >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    start "" pyw app.py
    exit /b 0
)

:: 2. Intentar lanzar sin ventana de consola mediante pythonw
where pythonw >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    start "" pythonw app.py
    exit /b 0
)

:: 3. Si pyw/pythonw no estuvieran disponibles, usar py normal
where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    start "" py -3 app.py
    exit /b 0
)

:: 4. Intentar con python normal
where python >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    start "" python app.py
    exit /b 0
)

:: 5. Si no se encuentra Python en el sistema, mostrar aviso
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
