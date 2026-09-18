@echo off
rem Gradle startup script for Windows.
rem gradle-wrapper.jar 随仓库提交；官方 jar 无 Main-Class，必须用 -classpath 指定主类。

setlocal
set PRG=%~dp0
set APP_HOME=%PRG:~0,-1%
set WRAPPER_JAR=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

if not exist "%WRAPPER_JAR%" goto noWrapper

java %GRADLE_OPTS% -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
set EXIT_CODE=%ERRORLEVEL%
goto end

:noWrapper
echo gradle-wrapper.jar not found under %APP_HOME%\gradle\wrapper; falling back to system gradle 1>&2
gradle %*
set EXIT_CODE=%ERRORLEVEL%

:end
endlocal & exit /b %EXIT_CODE%
