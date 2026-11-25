@echo off
setlocal
title Cursorly - Ultra-Precise Mobile Mouse Control

set PYTHON_EXEC=
if exist ".venv\Scripts\python.exe" (
    set "PYTHON_EXEC=.venv\Scripts\python.exe"
) else (
    py -3 -V >nul 2>&1
    if not errorlevel 1 (
        set "PYTHON_EXEC=py -3"
    ) else (
        python -V >nul 2>&1
        if not errorlevel 1 (
            set "PYTHON_EXEC=python"
        )
    )
)

if "%PYTHON_EXEC%"=="" (
    echo ====================================================================
    echo [ERROR] Python was not found!
    echo Run setup.bat first or install Python 3.10+ from python.org.
    echo ====================================================================
    pause
    exit /b 1
)

echo ====================================================================
echo   Launching Cursorly: Ultra-Precise Mobile Mouse Control
echo ====================================================================
%PYTHON_EXEC% -m cursorly.main %*
if errorlevel 1 (
    echo.
    echo Process exited with an error. Run 'setup.bat' to verify dependencies.
    pause
)
