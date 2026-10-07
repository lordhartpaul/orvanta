@echo off
rem Starts Orvanta Pay with config\orvanta.yaml. Needs a JDK 17 or later on the PATH (a JDK, not a JRE).
rem Extra arguments override settings, for example:  bin\start.cmd --server.port=8481 --units=api,ingest
setlocal
cd /d "%~dp0.."
java -jar lib\orvanta-pay-all.jar --config=config\orvanta.yaml %*
