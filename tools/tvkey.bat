@echo off
REM ===============================================================
REM  Send any remote-control key to the Android TV emulator.
REM
REM  >>> THIS IS A COMMAND-LINE TOOL - DOUBLE-CLICKING DOES NOTHING
REM  >>> USEFUL. Open Android Studio's Terminal tab and type:
REM  >>>
REM  >>>     tools\tvkey.bat 82
REM  >>>
REM  >>> (82 = MENU key, which opens the reader settings panel)
REM
REM  For a double-clickable version, use:  打开阅读设置.bat
REM
REM  AVAILABLE KEYS:
REM     82    MENU      - open reader settings
REM     176   SETTINGS  - same thing, some remotes send this
REM     23    OK / DPAD_CENTER
REM     4     BACK
REM     19    UP        20   DOWN
REM     21    LEFT      22   RIGHT
REM
REM  KEYS THIS TOOL IS INTENTIONALLY ASCII-ONLY:
REM  cmd.exe decodes .bat files with the OEM code page, so UTF-8
REM  Chinese comments would be read as garbage and executed.
REM ===============================================================

if "%~1"=="" (
    echo.
    echo   Usage:  tools\tvkey.bat ^<keycode^>
    echo.
    echo     82    MENU      - open reader settings
    echo     176   SETTINGS  - same as above
    echo     23    OK
    echo     4     BACK
    echo     19    UP        20   DOWN
    echo     21    LEFT      22   RIGHT
    echo.
    echo   Tip: run this from Android Studio's Terminal tab, not by
    echo        double-clicking. For a double-click version use
    echo        "打开阅读设置.bat".
    echo.
    exit /b 1
)

set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe

if not exist "%ADB%" (
    echo   [ERROR] adb not found at: %ADB%
    exit /b 1
)

"%ADB%" shell input keyevent %~1

if errorlevel 1 (
    echo   [ERROR] failed to send key. Is the emulator running?
    exit /b 1
)

echo   [OK] key %~1 sent.
