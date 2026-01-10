@echo off
echo Building Mercurius Uberjar...

echo.
echo This will create a single executable JAR file with all dependencies included
echo.

REM Clean and build the application
mvn clean package -DskipTests

if %ERRORLEVEL% neq 0 (
    echo.
    echo ERROR: Build failed!
    pause
    exit /b 1
)

echo.
echo Build completed successfully!
echo.
echo Uberjar location: target\mercurius-quarkus-runner.jar
echo.
echo To run the application:
echo   java -jar target\mercurius-quarkus-runner.jar
echo.
echo To run with AWT support for tray icon:
echo   java -Djava.awt.headless=false -jar target\mercurius-quarkus-runner.jar
echo.

pause