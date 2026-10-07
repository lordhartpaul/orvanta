@echo off
rem Runs every automated test and writes the use-case report (target\test-report.md).
rem   bin\test-all.cmd                 Java, model and browser tests
rem   set ORVANTA_IT_RABBIT=amqp://guest:guest@localhost:5672   to include the queue test against a real broker
rem First time only:  cd console-tests ^&^& npm install ^&^& npx playwright install chromium
setlocal
cd /d "%~dp0.."
set RABBIT=
if not "%ORVANTA_IT_RABBIT%"=="" set RABBIT=-Dorvanta.it.rabbit=%ORVANTA_IT_RABBIT%
rem   set ORVANTA_IT_MONGO=mongodb://localhost:27017   to run the list queries against a real MongoDB as well (own database, dropped afterwards)
if not "%ORVANTA_IT_MONGO%"=="" set RABBIT=%RABBIT% -Dorvanta.it.mongo=%ORVANTA_IT_MONGO%
rem   set ORVANTA_IT_KAFKA=localhost:9092   to run the topic test against a real Kafka broker as well
if not "%ORVANTA_IT_KAFKA%"=="" set RABBIT=%RABBIT% -Dorvanta.it.kafka=%ORVANTA_IT_KAFKA%
rem   set ORVANTA_IT_IBMMQ_HOST (and _PORT, _CHANNEL, _QUEUE_MANAGER, _QUEUE, _USER, _PASSWORD)   to probe a real IBM MQ queue manager as well
if not "%ORVANTA_IT_IBMMQ_HOST%"=="" set RABBIT=%RABBIT% -Dorvanta.it.ibmmq.host=%ORVANTA_IT_IBMMQ_HOST% -Dorvanta.it.ibmmq.port=%ORVANTA_IT_IBMMQ_PORT% -Dorvanta.it.ibmmq.channel=%ORVANTA_IT_IBMMQ_CHANNEL% -Dorvanta.it.ibmmq.queueManager=%ORVANTA_IT_IBMMQ_QUEUE_MANAGER% -Dorvanta.it.ibmmq.queue=%ORVANTA_IT_IBMMQ_QUEUE% -Dorvanta.it.ibmmq.user=%ORVANTA_IT_IBMMQ_USER% -Dorvanta.it.ibmmq.password=%ORVANTA_IT_IBMMQ_PASSWORD%
call mvnw.cmd -q -Prelease install %RABBIT%
if errorlevel 1 (
  echo Java tests failed
  python bin\test-report.py
  exit /b 1
)
pushd console-tests
call npx playwright test
if errorlevel 1 echo browser tests failed
popd
python bin\test-report.py
