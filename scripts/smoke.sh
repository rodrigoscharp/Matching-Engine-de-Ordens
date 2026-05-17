#!/usr/bin/env bash
# Smoke test for Athena Matching Engine.
# Runs basic HTTP checks against a live instance.
#
# TODO: implement full smoke test suite in Sprint 3 (REST adapter available).
# At that point, test: POST /api/v1/orders, GET /api/v1/books/{symbol},
#                      WebSocket stream, /actuator/health.

set -euo pipefail

echo "⚠  Smoke tests not yet implemented." >&2
echo "   This script will be completed in Sprint 3 once the REST adapter is live." >&2
echo "   Expected checks: order submission, book query, WebSocket stream, health." >&2
exit 1
