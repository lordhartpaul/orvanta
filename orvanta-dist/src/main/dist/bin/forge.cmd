@echo off
rem Orvanta Forge command line:  bin\forge.cmd validate^|test^|emit workspace [outputDir]
setlocal
cd /d "%~dp0.."
java -cp lib\orvanta-pay-all.jar io.orvanta.forge.ForgeCli %*
