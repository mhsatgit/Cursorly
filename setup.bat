@echo off
setlocal enabledelayedexpansion
title Cursorly - Setup & Environment Initializer

echo ====================================================================
echo   Cursorly: Setup ^& Virtual Environment Initializer
echo ====================================================================
echo.

:: 1. Detect Python
set PYTHON_CMD=
py -3 -V >nul 2>&1
if not errorlevel 1 (
    set PYTHON_CMD=py -3
    goto :python_found
)
python -V >nul 2>&1
if not errorlevel 1 (
    set PYTHON_CMD=python
    goto :python_found
)

echo [ERROR] Python 3.10+ was not found on your system!
echo Please install Python from https://www.python.org/downloads/
echo Make sure to check "Add Python to PATH" during installation.
pause
exit /b 1

:python_found
for /f "tokens=*" %%i in ('%PYTHON_CMD% -V') do set PY_VER=%%i
echo [*] Found: !PY_VER!

:: 2. Create Virtual Environment (.venv)
if not exist ".venv\Scripts\python.exe" (
    echo [*] Creating virtual environment (.venv)...
    %PYTHON_CMD% -m venv .venv
    if errorlevel 1 (
        echo [ERROR] Failed to create virtual environment.
        pause
        exit /b 1
    )
    echo [*] Virtual environment created successfully.
) else (
    echo [*] Existing virtual environment found (.venv).
)

:: 3. Upgrade pip and install dependencies
echo [*] Installing dependencies from requirements.txt...
.venv\Scripts\python.exe -m pip install --upgrade pip
.venv\Scripts\python.exe -m pip install -r requirements.txt
if errorlevel 1 (
    echo [ERROR] Dependency installation failed.
    pause
    exit /b 1
)

echo.
echo ====================================================================
echo   [SUCCESS] Cursorly is fully configured and ready!
echo ====================================================================
echo.
echo You can now launch Cursorly by double-clicking 'run_cursorly.bat'
echo or running:
echo     .venv\Scripts\python.exe -m cursorly.main
echo.
pause
