@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "ROOT=%~dp0"
set "MINECRAFT_VERSION=26.1.2"
set "MOD_VERSION="
for /F "tokens=1,* delims==" %%A in ('findstr /B /C:"mod_version=" "%ROOT%gradle.properties"') do set "MOD_VERSION=%%B"
if not defined MOD_VERSION (
    echo Could not read mod_version from gradle.properties.
    pause
    exit /b 1
)
set "SOURCE_JAR=%ROOT%versions\mc26_1_2\build\libs\kung-%MINECRAFT_VERSION%-%MOD_VERSION%.jar"
set "SOURCE_JAR_26_2=%ROOT%versions\mc26_2\build\libs\kung-26.2-%MOD_VERSION%.jar"
rem 26.3 is built and checked here, but no test pack runs it, so its jar stays in the build folder.
set "SOURCE_JAR_26_3=%ROOT%versions\mc26_3\build\libs\kung-26.3-%MOD_VERSION%.jar"
set "DEFAULT_PROFILE=Dungeons 26.1.2"
set "SECONDARY_PROFILE=Here We Go Again (2)"
rem The "Dungeons 26.1.2" folder is now the 26.2 pack (only its folder name is old), so with no
rem arguments it and "test" get the 26.2 jar and "Here We Go Again (2)" gets the 26.1.2 jar.
rem Profiles passed as arguments get the 26.1.2 jar only.
set "PROFILE_26_2=test"
set "PROFILE_NAME=%~1"
if "%PROFILE_NAME%"=="" set "PROFILE_NAME=%DEFAULT_PROFILE%"
set "PROFILE_DIR=%APPDATA%\ModrinthApp\profiles\%PROFILE_NAME%"
set "SCAN_DIR=%APPDATA%\ModrinthApp\profiles\%DEFAULT_PROFILE%\kung-dungeon-scans"
set "RESOURCE_SCAN_DIR=%ROOT%versions\mc26_1_2\src\main\resources\kung-dungeon-scans"

if /I "%KUNG_SYNC_LOCAL_ROOM_DATA%"=="true" goto sync_room_data
if "%KUNG_SYNC_LOCAL_ROOM_DATA%"=="1" goto sync_room_data
echo Using bundled room data from the repository. Set KUNG_SYNC_LOCAL_ROOM_DATA=true to bake local profile room data into the jar.
goto after_room_sync

:sync_room_data
echo Syncing learned room data into the jar resources...
if not exist "!RESOURCE_SCAN_DIR!" mkdir "!RESOURCE_SCAN_DIR!"

if exist "!SCAN_DIR!\known-rooms.json" (
    copy /Y "!SCAN_DIR!\known-rooms.json" "!RESOURCE_SCAN_DIR!\known-rooms.json" >nul
) else if exist "!SCAN_DIR!\known-rooms.jsonl" (
    copy /Y "!SCAN_DIR!\known-rooms.jsonl" "!RESOURCE_SCAN_DIR!\known-rooms.jsonl" >nul
) else (
    echo No known-rooms.json or known-rooms.jsonl found yet.
)

if exist "!SCAN_DIR!\known-room-preloads.jsonl" (
    copy /Y "!SCAN_DIR!\known-room-preloads.jsonl" "!RESOURCE_SCAN_DIR!\known-room-preloads.jsonl" >nul
)

if exist "!SCAN_DIR!\known-room-types.properties" (
    copy /Y "!SCAN_DIR!\known-room-types.properties" "!RESOURCE_SCAN_DIR!\known-room-types.properties" >nul
)

if exist "!RESOURCE_SCAN_DIR!\known-rooms.json" (
    node "%ROOT%tools\ensure-room-prince-field.mjs" "!RESOURCE_SCAN_DIR!\known-rooms.json"
    if errorlevel 1 (
        echo.
        echo Could not normalize Prince room data.
        pause
        exit /b 1
    )
)

:after_room_sync
echo Building kung jars for 26.1.2, 26.2 and 26.3...
pushd "!ROOT!" >nul
call "!ROOT!gradlew.bat" :versions:mc26_1_2:build :versions:mc26_2:build :versions:mc26_3:build
set "BUILD_RESULT=%ERRORLEVEL%"
popd >nul

if not "!BUILD_RESULT!"=="0" (
    echo.
    echo Build failed. Jar was not copied.
    pause
    exit /b !BUILD_RESULT!
)

for %%J in ("!SOURCE_JAR!" "!SOURCE_JAR_26_2!" "!SOURCE_JAR_26_3!") do if not exist "%%~J" (
    echo.
    echo Built jar was not found:
    echo %%~J
    pause
    exit /b 1
)

tasklist /FI "IMAGENAME eq javaw.exe" 2>nul | find /I "javaw.exe" >nul
if not errorlevel 1 (
    echo.
    echo Minecraft/Java is still running. Close all Minecraft windows before copying the jar.
    echo Built jar was left here:
    echo !SOURCE_JAR!
    pause
    exit /b 1
)

echo.
if "%~1"=="" (
    call :copyProfile "%DEFAULT_PROFILE%" "26.2" "!SOURCE_JAR_26_2!"
    if errorlevel 1 exit /b 1
    call :copyProfile "%SECONDARY_PROFILE%" "%MINECRAFT_VERSION%" "!SOURCE_JAR!"
    if errorlevel 1 exit /b 1
    call :copyProfile "%PROFILE_26_2%" "26.2" "!SOURCE_JAR_26_2!"
    if errorlevel 1 exit /b 1
    goto copied_all
)

:copy_loop
if "%~1"=="" goto copied_all
call :copyProfile "%~1" "%MINECRAFT_VERSION%" "!SOURCE_JAR!"
if errorlevel 1 exit /b 1
shift
goto copy_loop

:copied_all
echo.
echo Done.
echo.
certutil -hashfile "!SOURCE_JAR!" SHA256
certutil -hashfile "!SOURCE_JAR_26_2!" SHA256
certutil -hashfile "!SOURCE_JAR_26_3!" SHA256
pause
exit /b 0

:copyProfile
set "TARGET_PROFILE=%~1"
set "TARGET_MC=%~2"
set "TARGET_SOURCE=%~3"
set "TARGET_DIR=%APPDATA%\ModrinthApp\profiles\%TARGET_PROFILE%\mods"
set "TARGET_JAR=%TARGET_DIR%\kung-%TARGET_MC%-%MOD_VERSION%.jar"
set "STAGED_JAR=%TARGET_DIR%\kung-%TARGET_MC%-%MOD_VERSION%.jar.new"

if not exist "!TARGET_DIR!" (
    echo Creating mods folder:
    echo !TARGET_DIR!
    mkdir "!TARGET_DIR!"
)

rem Only the Modrinth App switches between the Kung jars: it ignores a jar a script flipped.
rem So the new build takes the GitHub jar's current state - active, unless the pack runs
rem the Modrinth jar or has Kung switched off - and the Modrinth jar is never touched.
set "TARGET_SUFFIX="
set "FOUND="
for %%F in ("!TARGET_DIR!\kung-%TARGET_MC%-*.jar*" "!TARGET_DIR!\kung-modrinth-%TARGET_MC%-*.jar*") do set "FOUND=1"
if defined FOUND set "TARGET_SUFFIX=.disabled"
for %%F in ("!TARGET_DIR!\kung-%TARGET_MC%-*.jar") do set "TARGET_SUFFIX="

echo Staging jar for Modrinth profile: !TARGET_PROFILE!
copy /Y "!TARGET_SOURCE!" "!STAGED_JAR!" >nul
if errorlevel 1 (
    echo.
    echo Staging failed:
    echo !STAGED_JAR!
    pause
    exit /b 1
)

echo Removing old Kung jars...
for %%F in ("!TARGET_DIR!\kung-%TARGET_MC%-*.jar" "!TARGET_DIR!\kung-%TARGET_MC%-*.jar.disabled") do (
    if exist "%%~fF" (
        echo   %%~nxF
        del /F /Q "%%~fF" >nul
        if exist "%%~fF" (
            echo.
            echo Could not remove old jar:
            echo %%~fF
            del /F /Q "!STAGED_JAR!" >nul 2>nul
            pause
            exit /b 1
        )
    )
)

move /Y "!STAGED_JAR!" "!TARGET_JAR!!TARGET_SUFFIX!" >nul
if errorlevel 1 (
    echo.
    echo Could not activate the new jar:
    echo !TARGET_JAR!!TARGET_SUFFIX!
    pause
    exit /b 1
)
echo Installed jar:
echo !TARGET_JAR!!TARGET_SUFFIX!
if defined TARGET_SUFFIX echo   Installed disabled: this pack runs the Modrinth jar or has Kung off. Switch in the Modrinth App.
exit /b 0
