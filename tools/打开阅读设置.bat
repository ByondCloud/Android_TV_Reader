@echo off
REM ===============================================================
REM  Double-click = press the MENU key on the Android TV emulator.
REM  This opens the reader settings panel inside the app.
REM
REM  WHY THIS FILE IS ASCII-ONLY:
REM  cmd.exe decodes .bat files with the system OEM code page
REM  (GBK on Chinese Windows). UTF-8 Chinese inside a .bat gets
REM  decoded into garbage which can even contain pipe characters,
REM  so the command line breaks apart. All Chinese output lives in
REM  the .ps1 instead, saved as UTF-8 WITH BOM so that Windows
REM  PowerShell 5.1 reads it correctly.
REM
REM  The final pause is what keeps this window open so you can
REM  actually read the result instead of seeing it flash by.
REM ===============================================================

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0open-reader-settings.ps1"

echo.
echo   Press any key to close this window...
pause >nul
