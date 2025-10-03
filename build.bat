@echo off
echo ========================================
echo   ICan Application - Building...
echo ========================================
echo.

REM 清理并打包项目(跳过测试)
echo Building project (skip tests)...
mvn clean package -DskipTests

echo.
echo ========================================
echo   Build complete!
echo   JAR location: target\ican-0.0.1-SNAPSHOT.jar
echo ========================================
echo.

pause
