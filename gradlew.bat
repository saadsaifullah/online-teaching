@echo off
setlocal
set "OT_ROOT=%~dp0"
if not exist "%OT_ROOT%gradle\wrapper\gradle-wrapper.jar" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%OT_ROOT%scripts\bootstrap-wrapper.ps1"
  if errorlevel 1 exit /b 1
)
if defined JAVA_HOME (set "OT_JAVA=%JAVA_HOME%\bin\java.exe") else (set "OT_JAVA=java")
"%OT_JAVA%" -Dorg.gradle.appname=gradlew -classpath "%OT_ROOT%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
