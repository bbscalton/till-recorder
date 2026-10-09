@echo off
setlocal EnableExtensions EnableDelayedExpansion
title Install Till Recorder
cd /d "%~dp0"

set "ADB="
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
  set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
)
if not defined ADB (
  where adb >nul 2>&1
  if not errorlevel 1 (
    for /f "delims=" %%A in ('where adb') do (
      if not defined ADB set "ADB=%%A"
    )
  )
)
if not defined ADB (
  echo Could not find adb.
  echo Install Android SDK platform-tools, or put adb.exe on PATH.
  echo Expected: %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
  echo.
  pause
  exit /b 1
)
echo Using adb:
echo   !ADB!
echo.

set "APK="
set "OUTDIR=%~dp0android\app\build\outputs"
if exist "%OUTDIR%" (
  for /f "usebackq delims=" %%F in (`powershell -NoProfile -Command "Get-ChildItem -LiteralPath '%OUTDIR%' -Filter *.apk -Recurse -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName"`) do (
    if not defined APK set "APK=%%F"
  )
)

if not defined APK (
  echo No local APK was found. Downloading the published Till Recorder APK.
  set "APK=%TEMP%\till-recorder-install.apk"
  curl.exe -fsSL -o "!APK!" "https://github.com/bbscalton/till-recorder/releases/download/v1.5.12/till-recorder.apk"
  if errorlevel 1 (
    echo Download failed.
    echo.
    pause
    exit /b 1
  )
)

echo Using APK:
echo   !APK!
echo.

set "DEVLIST=%TEMP%\till-recorder-devices.txt"
"!ADB!" devices > "!DEVLIST!"
if errorlevel 1 (
  echo adb devices failed.
  echo.
  pause
  exit /b 1
)

set "SAW=0"
set "INSTALLED=0"
for /f "usebackq tokens=1,2" %%A in ("!DEVLIST!") do (
  if /I "%%B"=="device" (
    set "SAW=1"
    echo Installing on %%A
    "!ADB!" -s "%%A" install -r "!APK!"
    if errorlevel 1 (
      echo Install failed on %%A
    ) else (
      echo Installed on %%A
      set /a INSTALLED+=1
    )
    echo.
  ) else if /I "%%B"=="unauthorized" (
    set "SAW=1"
    echo %%A is not authorized.
    echo Unlock the phone and tap Allow on the USB debugging prompt, then run this again.
    echo.
  )
)

if "!SAW!"=="0" (
  echo No Android device is connected.
  echo Plug in the phone, allow USB debugging, and run this again.
  echo.
  pause
  exit /b 1
)

echo Finished. Installed on !INSTALLED! device(s).
echo.
pause
exit /b 0
