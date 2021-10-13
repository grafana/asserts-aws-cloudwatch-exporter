#!/bin/sh

set -o errexit

. kind-cleanup.sh

echo "Creating kind-aws-exporter Cluster"
kind create cluster --name=aws-exporter

docker build -t 543343501704.dkr.ecr.us-west-2.amazonaws.com/ai.asserts.aws-exporter:0.1.0 .

kind load docker-image 543343501704.dkr.ecr.us-west-2.amazonaws.com/ai.asserts.aws-exporter:0.1.0 --name=aws-exporter

kubectl create ns aws-exporter

helm dep up chart

helm -n aws-exporter install aws-exporter ./chart

# Give Prometheus Pod time to spin up before trying to Port Froward it to localhost
sleep 30