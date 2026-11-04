@echo off
title Compilador de OmniVault Desktop
cls
echo ========================================================
echo        COMPILADOR OFICIAL DE OMNIVAULT DESKTOP
echo ========================================================
echo.
echo Compilando aplicacion a binario ejecutable Windows (.exe)...
echo Este proceso toma entre 1 a 2 minutos.
echo.
py build_exe.py
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Hubo un problema durante la compilacion.
    pause
    exit /b 1
)
echo.
echo ========================================================
echo        LISTO: Revisa la carpeta dist\OmniVault\
echo ========================================================
echo.
pause
