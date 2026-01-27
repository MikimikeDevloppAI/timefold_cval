@echo off
cd /d "%~dp0"
for /f "tokens=1,2 delims==" %%a in (.env) do (
    set "%%a=%%b"
)
java -jar target\staff-scheduler-1.0-SNAPSHOT.jar %*
