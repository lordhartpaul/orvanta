@echo off
rem Builds what changed and starts Orvanta Pay from the source tree, in one step.
rem   bin\run.cmd                         build, then start with config\orvanta.yaml
rem   bin\run.cmd --workspace.syncOnStart=true   also deploy the models in workspace\ (development)
setlocal
cd /d "%~dp0.."
call mvnw.cmd -q -DskipTests install
if errorlevel 1 exit /b 1
java -jar orvanta-pay\target\orvanta-pay-0.1.0-all.jar --config=config\orvanta.yaml %*
