#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q test
npm run contracts:typecheck
npm --workspace packages/api-contracts test
npm run frontend:typecheck
npm run frontend:test
npm run frontend:build
npm run docs:build
docker compose -f infra/compose.yaml config --quiet
bash -n scripts/start-local.sh scripts/fixtures.sh
git diff --check
