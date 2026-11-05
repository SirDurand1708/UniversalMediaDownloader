@echo off
title Empaquetador de Lanzamientos - OmniVault Desktop
cls
echo ========================================================
echo   EMPAQUETADOR OFICIAL DE LANZAMIENTOS (GITHUB RELEASE)
echo ========================================================
echo.
echo Este script creara:
echo   1. Version Portatil: OmniVault-v2.0.0-Windows-x64-Portable.zip
echo   2. Instalador Setup: OmniVault-v2.0.0-Windows-x64-Setup.exe
echo   3. Sumas de Integridad: SHA256SUMS.txt
echo.
echo ========================================================
echo.

where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    py package_release.py
    goto FIN
)

where python >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    python package_release.py
    goto FIN
)

echo [ERROR] No se encontro Python en el sistema.
pause
exit /b 1

:FIN
if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================
    echo   LISTO: Revisa los paquetes en dist\Releases\
    echo ========================================================
) else (
    echo.
    echo [ERROR] Ocurrio un problema durante el empaquetado.
)
echo.
pause
