#!/usr/bin/env bash
set -e
cd $(dirname "$0")
cd ..

export APPVERSION=$(cat backend/VERSION)

docker build -t quay.io/n1analytics/entity-app:$APPVERSION backend
docker build -t quay.io/n1analytics/entity-nginx:v1.2.6 frontend
