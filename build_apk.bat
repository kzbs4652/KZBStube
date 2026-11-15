@echo off
echo ==============================================
echo   KZBStube - Otomatik APK Derleme Araci
echo ==============================================
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot
set ANDROID_HOME=C:\Android\sdk
call gradlew.bat assembleDebug
if %ERRORLEVEL% EQU 0 (
    if not exist apk mkdir apk
    copy /y app\build\outputs\apk\debug\app-debug.apk apk\KZBStube_v1.0.1.apk
    copy /y app\build\outputs\apk\debug\app-debug.apk apk\KZBStube.apk
    echo.
    echo [BASARILI] Yeni APK dosyaniz olusturuldu: apk\KZBStube_v1.0.1.apk
) else (
    echo.
    echo [HATA] Derleme sirasinda bir hata olustu.
)
pause
