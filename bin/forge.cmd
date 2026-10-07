@echo off
rem Orvanta Forge command line.
rem   bin\forge.cmd validate workspace        compile the models and report problems
rem   bin\forge.cmd test workspace            compile, then run every TestCase model
rem   bin\forge.cmd emit workspace target\gen write the generated Java sources
setlocal
cd /d "%~dp0.."
java -cp orvanta-pay\target\orvanta-pay-0.1.0-all.jar io.orvanta.forge.ForgeCli %*
