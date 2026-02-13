#!/usr/bin/env bash
set -euo pipefail

./gradlew installDist
docker compose up -d --force-recreate
