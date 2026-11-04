@echo off
title OmniVault Desktop - Universal Media Hub
cd /d "%~dp0"

:: 1. Intentar lanzar con pythonw directo (mas rapido y sin consola)
where pythonw >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    start "" pythonw app.py
    exit /b 0
)

:: 2. Intentar lanzar sin ventana de consola mediante pyw
where pyw >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    start "" pyw app.py
    exit /b 0
)

:: 3. Intentar con py normal
where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    start "" py app.py
    exit /b 0
)

:: 4. Intentar con python normal
where python >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    start "" python app.py
    exit /b 0
)

:: 5. Si no esta en PATH, buscar en rutas comunes de Python en Windows
if exist "%LOCALAPPDATA%\Programs\Python\Python*\pythonw.exe" (
    for /d %%D in ("%LOCALAPPDATA%\Programs\Python\Python*") do (
        if exist "%%D\pythonw.exe" (
            start "" "%%D\pythonw.exe" app.py
            exit /b 0
        )
    )
)

if exist "%LOCALAPPDATA%\Python\pythoncore-*\pythonw.exe" (
    for /d %%D in ("%LOCALAPPDATA%\Python\pythoncore-*") do (
        if exist "%%D\pythonw.exe" (
            start "" "%%D\pythonw.exe" app.py
            exit /b 0
        )
    )
)

echo ==============================================================
echo  ERROR: No se encontro Python en el sistema o en el PATH.
echo ==============================================================
echo  Para ejecutar OmniVault Desktop:
echo  1. Instala Python desde https://www.python.org/downloads/
echo  2. Asegurate de marcar "Add Python to PATH" durante la instalacion.
echo ==============================================================
echo.
pause
exit /b 1
