@echo off
set JAVA_HOME=C:\Users\micha\scoop\apps\temurin17-jdk\17.0.17-10
cd /d c:\Users\micha\OneDrive\New_db_cval\timefold-solver
C:\Users\micha\scoop\apps\maven\3.9.12\bin\mvn.cmd clean package -DskipTests
