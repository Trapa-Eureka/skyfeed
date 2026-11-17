#!/usr/bin/env bash
# The optional headless CMS (Strapi): operator notices and sensor site details for the console.
# Usage: bash scripts/cms.sh up      build the CMS image if needed and start the stack with the CMS
#        bash scripts/cms.sh down    stop the CMS; the rest of the stack keeps running, without CMS content
#        bash scripts/cms.sh check   with the CMS up: check it and the console's use of it (R-CMS-01)
# Building the CMS image takes about 1.9 GB of memory for half a minute, more than a 3 GB Docker has to spare
# beside the running stack. So `up` stops the stack, builds, and then starts everything.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then set -a; . ./.env; set +a; fi
CMS="http://127.0.0.1:${CMS_PORT:-1337}"
WEB="http://127.0.0.1:${WEB_PORT:-3000}"
export CMS_URL_IN_STACK="http://cms:1337"

pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1" >&2; exit 1; }
jq_node() { node -e 'let s="";process.stdin.on("data",c=>s+=c).on("end",()=>{try{const d=JSON.parse(s);console.log(eval(process.argv[1]))}catch{console.log("")}})' "$1"; }

case "${1:-}" in
  up)
    echo "Stopping the stack while the CMS image is built..."
    docker compose --profile cms stop > /dev/null 2>&1 || true
    docker compose --profile cms build cms
    echo "Starting the stack with the CMS..."
    CMS_URL="$CMS_URL_IN_STACK" docker compose --profile cms up -d --build --wait
    echo "CMS admin panel: $CMS/admin (the first visit asks you to create the administrator)"
    echo "Console with CMS content: $WEB"
    ;;
  down)
    docker compose --profile cms stop cms
    docker compose up -d --wait web # the console again without CMS_URL
    echo "The CMS is stopped. Its content is kept in the Docker volume skyfeed_cms-data."
    ;;
  check)
    # R-CMS-01
    sites="$(curl -gfsS "$CMS/api/sensor-sites?pagination[pageSize]=100" | jq_node 'd.data.length')" || fail "the CMS does not answer at $CMS; start it with: pnpm cms:up"
    [ "${sites:-0}" -ge 5 ] || fail "expected at least 5 sensor sites in the CMS, got $sites"
    pass "the CMS serves the sensor sites ($sites)"

    for method in POST PUT DELETE; do
      code="$(curl -s -o /dev/null -w '%{http_code}' -X "$method" "$CMS/api/notices" -H 'Content-Type: application/json' -d '{"data":{"title":"x","body":"y","severity":"info"}}')"
      case "$code" in 401 | 403 | 404 | 405) ;; *) fail "the CMS answered $code to a $method without signing in; it must refuse" ;; esac
    done
    pass "the CMS refuses to change content for a visitor who is not signed in"

    via_web="$(curl -fsS "$WEB/api/cms/notices" | jq_node 'd.state + ":" + d.items.length')"
    case "$via_web" in ok:[1-9]*) ;; *) fail "the console's /api/cms/notices answered '$via_web' instead of ok with notices" ;; esac
    pass "the console reads the notices through its own /api/cms path ($via_web)"

    CMS=1 pnpm --filter @skyfeed/web exec playwright test cms.spec || fail "the console did not show the CMS content"
    pass "the console shows the notices and the site details of a sensor"
    echo "CMS check passed."
    ;;
  *)
    echo "usage: bash scripts/cms.sh up|down|check" >&2
    exit 2
    ;;
esac
