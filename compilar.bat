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

where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    py build_exe.py
    goto POST_BUILD
)

where python >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    python build_exe.py
    goto POST_BUILD
)

echo [ERROR] No se encontro Python ni py en el sistema.
pause
exit /b 1

:POST_BUILD
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
