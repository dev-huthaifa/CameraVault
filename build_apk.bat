@echo off
chcp 65001 > nul
echo ========================================================
echo   Secret Vault Camera (تطبيق الخزنة المشفرة والكاميرا)
echo   جاري بناء ملف التطبيق APK...
echo ========================================================

set "GRADLE_JAR=C:\Users\الادارة\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\lib\gradle-launcher-8.9.jar"

if exist "%GRADLE_JAR%" (
    java -Dfile.encoding=UTF-8 -cp "%GRADLE_JAR%" org.gradle.launcher.GradleMain assembleDebug
) else (
    echo [خطأ] تعذر العثور على مشغل جردل. يرجى فتح المشروع من Android Studio.
    pause
    exit /b 1
)

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================
    echo   [نجاح] تم بناء ملف التطبيق بنجاح!
    echo   المسار: app\build\outputs\apk\debug\app-debug.apk
    echo ========================================================
) else (
    echo.
    echo [خطأ] حدث خطأ أثناء بناء التطبيق.
)
pause
