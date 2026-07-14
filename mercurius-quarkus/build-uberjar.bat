@echo off
echo ====================================
echo Building Mercurius Uberjar
echo ====================================
echo.

echo Cleaning previous builds...
call mvn clean

echo.
echo Building uberjar with MyFaces 4.1.0...
call mvn package -DskipTests

echo.
echo ====================================
echo Build Complete!
echo ====================================
echo.

if exist "target\mercurius-quarkus-runner.jar" (
    echo ✅ Uberjar created successfully!
    echo 📦 Location: target\mercurius-quarkus-runner.jar
    echo 📏 Size: 
    dir "target\mercurius-quarkus-runner.jar" | find "mercurius-quarkus-runner.jar"
    echo.
    echo 🚀 To run: java -jar target\mercurius-quarkus-runner.jar
    echo 🌐 Application will be available at: http://localhost:8081/Mercurius
) else (
    echo ❌ Build failed! Check the error messages above.
)

echo.
pause