@echo off
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot
set ANDROID_HOME=C:\Users\E439~1\AppData\Local\Android\Sdk
set GRADLE_USER_HOME=C:\Users\E439~1\.gradle
cd /d S:\SecretVault

"%JAVA_HOME%\bin\java.exe" -Duser.home=C:\Users\E439~1 -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8 -Dorg.gradle.appname=gradlew -classpath "C:\Users\E439~1\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\lib\gradle-launcher-8.9.jar" org.gradle.launcher.GradleMain --stop
"%JAVA_HOME%\bin\java.exe" -Duser.home=C:\Users\E439~1 -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8 -Dorg.gradle.appname=gradlew -classpath "C:\Users\E439~1\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\lib\gradle-launcher-8.9.jar" org.gradle.launcher.GradleMain assembleRelease bundleRelease
