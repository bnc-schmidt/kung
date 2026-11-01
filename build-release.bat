@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem Builds and tests every release jar, then collects them in release\<mod_version>:
rem   github\    kung-26.1.2, kung-26.2 and kung-26.3 - attach all to the GitHub release
rem   modrinth\  kung-modrinth-26.1.2, -26.2 and -26.3 - one Modrinth version each, never on GitHub
rem Then it installs both flavors into the test packs below. Whichever Kung a pack runs stays
rem active (the Modrinth or the GitHub jar); the other one is added as .disabled.

set "ROOT=%~dp0"
set "MOD_VERSION="
for /F "tokens=1,* delims==" %%A in ('findstr /B /C:"mod_version=" "%ROOT%gradle.properties"') do set "MOD_VERSION=%%B"
if not defined MOD_VERSION (
    echo Could not read mod_version from gradle.properties.
    pause
    exit /b 1
)

pushd "%ROOT%"
echo Building the full jars...
call "%ROOT%gradlew.bat" build
if errorlevel 1 goto failed
echo Building the Modrinth jars...
call "%ROOT%gradlew.bat" build -Pmodrinth
if errorlevel 1 goto failed

set "OUT=%ROOT%release\%MOD_VERSION%"
if exist "%OUT%" rmdir /S /Q "%OUT%"
mkdir "%OUT%\github" "%OUT%\modrinth"
copy /Y "versions\mc26_1_2\build\libs\kung-26.1.2-%MOD_VERSION%.jar" "%OUT%\github\" >nul || goto failed
copy /Y "versions\mc26_2\build\libs\kung-26.2-%MOD_VERSION%.jar" "%OUT%\github\" >nul || goto failed
copy /Y "versions\mc26_3\build\libs\kung-26.3-%MOD_VERSION%.jar" "%OUT%\github\" >nul || goto failed
copy /Y "versions\mc26_1_2\build\modrinth\libs\kung-modrinth-26.1.2-%MOD_VERSION%.jar" "%OUT%\modrinth\" >nul || goto failed
copy /Y "versions\mc26_2\build\modrinth\libs\kung-modrinth-26.2-%MOD_VERSION%.jar" "%OUT%\modrinth\" >nul || goto failed
copy /Y "versions\mc26_3\build\modrinth\libs\kung-modrinth-26.3-%MOD_VERSION%.jar" "%OUT%\modrinth\" >nul || goto failed
popd

echo.
echo Release %MOD_VERSION% is ready:
dir /B /S "%OUT%\*.jar"

echo.
tasklist /FI "IMAGENAME eq javaw.exe" 2>nul | find /I "javaw.exe" >nul
if not errorlevel 1 (
    echo Minecraft/Java is still running, so the test packs were left as they are.
    pause
    exit /b 0
)
echo Installing into the test packs...
call :install "Dungeons 26.1.2" 26.1.2
if errorlevel 1 goto install_failed
call :install "Here We Go Again (2)" 26.1.2
if errorlevel 1 goto install_failed
call :install "test" 26.2
if errorlevel 1 goto install_failed
pause
exit /b 0

:failed
popd
echo.
echo Build failed; no release jars were collected.
pause
exit /b 1

:install_failed
echo.
echo Installing into the test packs failed.
pause
exit /b 1

:install
set "PROFILE=%~1"
set "MC=%~2"
set "MODS=%APPDATA%\ModrinthApp\profiles\%~1\mods"
if not exist "!MODS!" (
    echo   !PROFILE!: no mods folder, skipped
    exit /b 0
)
rem The flavor the pack runs now: Modrinth, GitHub, or none if Kung is switched off entirely.
set "ACTIVE=github"
set "FOUND="
for %%F in ("!MODS!\kung-!MC!-*.jar*" "!MODS!\kung-modrinth-!MC!-*.jar*") do set "FOUND=1"
if defined FOUND set "ACTIVE=none"
for %%F in ("!MODS!\kung-!MC!-*.jar") do set "ACTIVE=github"
for %%F in ("!MODS!\kung-modrinth-!MC!-*.jar") do set "ACTIVE=modrinth"

for %%F in ("!MODS!\kung-!MC!-*.jar*" "!MODS!\kung-modrinth-!MC!-*.jar*") do (
    del /F /Q "%%~fF" >nul 2>nul
    if exist "%%~fF" (
        echo   !PROFILE!: could not remove %%~nxF
        exit /b 1
    )
)

set "GITHUB_JAR=kung-!MC!-%MOD_VERSION%.jar"
set "MODRINTH_JAR=kung-modrinth-!MC!-%MOD_VERSION%.jar"
set "GITHUB_SUFFIX=.disabled"
set "MODRINTH_SUFFIX=.disabled"
if "!ACTIVE!"=="github" set "GITHUB_SUFFIX="
if "!ACTIVE!"=="modrinth" set "MODRINTH_SUFFIX="
copy /Y "!OUT!\github\!GITHUB_JAR!" "!MODS!\!GITHUB_JAR!!GITHUB_SUFFIX!" >nul || exit /b 1
copy /Y "!OUT!\modrinth\!MODRINTH_JAR!" "!MODS!\!MODRINTH_JAR!!MODRINTH_SUFFIX!" >nul || exit /b 1
echo   !PROFILE!: !ACTIVE! active
exit /b 0
