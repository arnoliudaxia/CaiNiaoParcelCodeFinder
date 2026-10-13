@echo off
rem Minimal Windows launcher for the bundled Gradle Wrapper.
setlocal
set "APP_HOME=%~dp0"
set "JAVA_EXE=java.exe"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Set JAVA_HOME to a JDK installation or put java.exe on PATH. 1>&2
    exit /b 1
)
"%JAVA_EXE%" -Xmx64m -Xms64m %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=gradlew" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
