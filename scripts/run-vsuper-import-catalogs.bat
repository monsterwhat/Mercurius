@echo off
setlocal
if "%DB_URL%"=="" set "DB_URL=jdbc:postgresql://localhost:5434/mercurius"
if "%DB_USERNAME%"=="" set "DB_USERNAME=mercurius"
if "%DB_PASSWORD%"=="" set "DB_PASSWORD=Mercurius@1!"
if "%VSUPER_CSV_DIR%"=="" set "VSUPER_CSV_DIR=D:\Documents\Facturas Electronicas\vSuper\csv"
if "%POSTGRES_JAR%"=="" set "POSTGRES_JAR=C:\Users\OIJ\.m2\repository\org\postgresql\postgresql\42.7.13\postgresql-42.7.13.jar"
pushd "%~dp0"
javac -cp "%POSTGRES_JAR%" "vsuper-import-catalogs.java"
if errorlevel 1 (echo Compile failed. & exit /b 1)
java -cp ".;%POSTGRES_JAR%" vsuper_import_catalogs --csv "%VSUPER_CSV_DIR%" --db "%DB_URL%" --user "%DB_USERNAME%" --password "%DB_PASSWORD%"
popd