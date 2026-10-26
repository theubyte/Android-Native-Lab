@echo off
setlocal
cd /d "%~dp0"
set "WRAPPER_JAR=%~dp0gradle\wrapper\gradle-wrapper.jar"
if not exist "%WRAPPER_JAR%" (
  echo Downloading verified official Gradle 8.9 wrapper JAR...
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.9-wrapper.jar' -OutFile '%WRAPPER_JAR%'"
  if errorlevel 1 exit /b 1
)
for /f "usebackq delims=" %%H in (`powershell.exe -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%WRAPPER_JAR%').Hash.ToLower()"`) do set "ACTUAL_SHA=%%H"
if /I not "%ACTUAL_SHA%"=="498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17" (
  echo ERROR: Invalid Gradle wrapper JAR checksum.
  exit /b 1
)
if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" -cp "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
