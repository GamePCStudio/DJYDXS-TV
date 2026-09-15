@echo off
set APP_HOME=%~dp0..
if exist "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" (
  java -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %*
) else (
  gradle %*
)
