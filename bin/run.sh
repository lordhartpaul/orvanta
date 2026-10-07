#!/bin/sh
# Builds what changed and starts Orvanta Pay from the source tree, in one step.
#   bin/run.sh                               build, then start with config/orvanta.yaml
#   bin/run.sh --workspace.syncOnStart=true  also deploy the models in workspace/ (development)
cd "$(dirname "$0")/.." || exit 1
./mvnw -q -DskipTests install || exit 1
exec java -jar orvanta-pay/target/orvanta-pay-0.1.0-all.jar --config=config/orvanta.yaml "$@"
