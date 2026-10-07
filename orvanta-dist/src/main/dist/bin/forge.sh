#!/bin/sh
# Orvanta Forge command line:  bin/forge.sh validate|test|emit workspace [outputDir]
cd "$(dirname "$0")/.." || exit 1
exec java -cp lib/orvanta-pay-all.jar io.orvanta.forge.ForgeCli "$@"
