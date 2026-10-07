@echo off
rem Starts Orvanta Pay (all services in one process) with config\orvanta.yaml.
rem Extra arguments override settings, for example:  bin\start.cmd --server.port=8481 --units=api
setlocal
cd /d "%~dp0.."
if not exist orvanta-pay\target\orvanta-pay-0.1.0-all.jar (
  echo Build first:  mvn install
  exit /b 1
)
java -jar orvanta-pay\target\orvanta-pay-0.1.0-all.jar --config=config\orvanta.yaml %*
