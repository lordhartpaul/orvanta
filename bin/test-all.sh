#!/bin/sh
# Runs every automated test and writes the use-case report (target/test-report.md).
#   bin/test-all.sh                                             Java, model and browser tests
#   ORVANTA_IT_RABBIT=amqp://guest:guest@localhost:5672 bin/test-all.sh    also the queue test against a real broker
# First time only:  (cd console-tests && npm install && npx playwright install chromium)
cd "$(dirname "$0")/.." || exit 1
RABBIT=""
[ -n "$ORVANTA_IT_RABBIT" ] && RABBIT="-Dorvanta.it.rabbit=$ORVANTA_IT_RABBIT"
# ORVANTA_IT_MONGO=mongodb://localhost:27017 also runs the list queries against a real MongoDB (in a database of its own, dropped afterwards)
[ -n "$ORVANTA_IT_MONGO" ] && RABBIT="$RABBIT -Dorvanta.it.mongo=$ORVANTA_IT_MONGO"
# ORVANTA_IT_KAFKA=localhost:9092 also runs the topic test against a real Kafka broker
[ -n "$ORVANTA_IT_KAFKA" ] && RABBIT="$RABBIT -Dorvanta.it.kafka=$ORVANTA_IT_KAFKA"
# ORVANTA_IT_IBMMQ_HOST, _PORT, _CHANNEL, _QUEUE_MANAGER, _QUEUE, _USER, _PASSWORD also probe a real IBM MQ queue manager (nothing is read or put)
if [ -n "$ORVANTA_IT_IBMMQ_HOST" ]; then
  RABBIT="$RABBIT -Dorvanta.it.ibmmq.host=$ORVANTA_IT_IBMMQ_HOST -Dorvanta.it.ibmmq.port=$ORVANTA_IT_IBMMQ_PORT -Dorvanta.it.ibmmq.channel=$ORVANTA_IT_IBMMQ_CHANNEL"
  RABBIT="$RABBIT -Dorvanta.it.ibmmq.queueManager=$ORVANTA_IT_IBMMQ_QUEUE_MANAGER -Dorvanta.it.ibmmq.queue=$ORVANTA_IT_IBMMQ_QUEUE -Dorvanta.it.ibmmq.user=$ORVANTA_IT_IBMMQ_USER -Dorvanta.it.ibmmq.password=$ORVANTA_IT_IBMMQ_PASSWORD"
fi
./mvnw -q -Prelease install $RABBIT || { echo "Java tests failed"; python bin/test-report.py; exit 1; }
(cd console-tests && npx playwright test) || echo "browser tests failed"
python bin/test-report.py
