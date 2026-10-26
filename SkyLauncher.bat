@echo off
setlocal EnableExtensions
cd /d "%~dp0"

if not exist "%~dp0skylauncher.pyw" (
    echo ERROR: skylauncher.pyw was not found next to this file.
    pause
    exit /b 1
)

set "PYW="

REM First, use an existing Python 3.9+ installation via the Python launcher.
where py >nul 2>nul
if not errorlevel 1 (
    py -3 -c "import sys; raise SystemExit(0 if sys.version_info >= (3,9) else 1)" >nul 2>nul
    if not errorlevel 1 (
        set "PYW=pyw -3"
        goto launch
    )
)

REM Try an existing pythonw.exe on PATH.
where pythonw >nul 2>nul
if not errorlevel 1 (
    python -c "import sys; raise SystemExit(0 if sys.version_info >= (3,9) else 1)" >nul 2>nul
    if not errorlevel 1 (
        set "PYW=pythonw"
        goto launch
    )
)

echo Python 3.9 or newer was not found. Installing Python 3.12 for this Windows user...
set "PYDIR=%LOCALAPPDATA%\SkyLauncher\Python312"
set "INSTALLER=%TEMP%\skylauncher-python-3.12.10.exe"

if not exist "%PYDIR%\pythonw.exe" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Invoke-WebRequest -Uri 'https://www.python.org/ftp/python/3.12.10/python-3.12.10-amd64.exe' -OutFile '%INSTALLER%' -UseBasicParsing } catch { exit 1 }"
    if errorlevel 1 (
        echo ERROR: Could not download Python. Check your internet connection.
        pause
        exit /b 1
    )
    "%INSTALLER%" /quiet InstallAllUsers=0 TargetDir="%PYDIR%" PrependPath=0 Include_launcher=0 Include_pip=1 Include_tcltk=1 Include_test=0
    if errorlevel 1 (
        echo ERROR: Python installation failed.
        pause
        exit /b 1
    )
    del "%INSTALLER%" >nul 2>nul
)

if not exist "%PYDIR%\pythonw.exe" (
    echo ERROR: Python installation completed, but pythonw.exe was not found.
    pause
    exit /b 1
)
set "PYW=%PYDIR%\pythonw.exe"

:launch
REM Start the launcher without opening a console window.
if "%PYW%"=="pyw -3" (
    start "" /b pyw -3 "%~dp0skylauncher.pyw"
) else (
    start "" /b "%PYW%" "%~dp0skylauncher.pyw"
)
exit /b 0
