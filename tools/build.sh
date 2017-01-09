#!/usr/bin/env bash
set -e
cd backend
docker pull python:3.5
docker build -t quay.io/n1analytics/entity-app:v1.2.4 .
cd ../frontend
docker build -t quay.io/n1analytics/entity-nginx:v1.2 .
cd ../db-server
docker build -t quay.io/n1analytics/entity-db-server:v1 .
cd ..
