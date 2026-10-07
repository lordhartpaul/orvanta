#!/bin/sh
# Starts Orvanta Pay with config/orvanta.yaml. Needs a JDK 17 or later on the PATH (a JDK, not a JRE).
# Extra arguments override settings, for example:  bin/start.sh --server.port=8481 --units=api,ingest
cd "$(dirname "$0")/.." || exit 1
exec java -jar lib/orvanta-pay-all.jar --config=config/orvanta.yaml "$@"
