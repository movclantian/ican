@echo off
echo ========================================
echo   ICan Application - Starting...
echo ========================================
echo.

REM 检查 Java 版本
echo Checking Java version...
java -version
echo.

REM 检查 Maven 版本
echo Checking Maven version...
mvn -version
echo.

REM 清理并编译项目
echo Cleaning and compiling project...
mvn clean compile
echo.

REM 启动应用
echo Starting application...
mvn spring-boot:run

pause
