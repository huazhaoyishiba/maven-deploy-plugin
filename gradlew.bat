@echo off
REM ============================================================
REM  Gradle wrapper 启动脚本 — 首次运行自动下载 Gradle
REM ============================================================
setlocal enabledelayedexpansion

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.

set APP_HOME=%DIRNAME%

set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

if not exist "%CLASSPATH%" (
    echo [INFO] gradle-wrapper.jar 不存在，正在下载...
    mkdir "%APP_HOME%\gradle\wrapper" 2>nul
    powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://github.com/gradle/gradle/raw/v8.7.0/gradle/wrapper/gradle-wrapper.jar' -OutFile '%CLASSPATH%'}" 2>nul
    if not exist "%CLASSPATH%" (
        powershell -Command "& {Invoke-WebRequest -Uri 'https://services.gradle.org/updates/distributions/gradle-8.7-wrapper.jar.sha256' -OutFile 'NUL'}" >nul 2>&1
        echo [WARN] 无法自动下载 gradle-wrapper.jar
        echo [WARN] 请在 IntelliJ IDEA 中直接打开此项目，IDEA 会自动处理
        echo [WARN] 或手动从 https://gradle.org/releases/ 下载
        pause
        exit /b 1
    )
    echo [INFO] gradle-wrapper.jar 下载完成
)

set GRADLE_OPTS=%GRADLE_OPTS% -Dorg.gradle.appname=gradlew

java %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

endlocal
