@echo off
echo Starting Mercurius Quarkus in Development Mode...
echo.
echo Application will be available at: http://localhost:8081/Mercurius
echo Debug port: 5005
echo Press Ctrl+C to stop the server
echo.

mvn quarkus:dev

pause