#!/bin/sh

set -o errexit

echo "Checking if kind-aws-exporter cluster exists"
if [[ $(kind get clusters | grep aws-exporter) ]]; then
    echo "Deleting existing kind-aws-exporter cluster"
    kind delete cluster --name=aws-exporter
fi
