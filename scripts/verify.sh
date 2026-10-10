#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd)"

(cd "$repository_root/api" && mvn test)
(cd "$repository_root/portal" && npm ci && npm run typecheck && npm run build)
(cd "$repository_root/technician" && npm ci && npm run typecheck && npx expo install --check && npx expo export --platform ios)
(cd "$repository_root/portal" && npx playwright install chromium && npm run test:e2e)
