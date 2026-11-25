#!/bin/bash
# Quick checks of a deployed hbproxy. Usage:  scripts/smoke-test.sh <https://liberty-host:7443> <bearer-token> [/hbscript/NAME]
# The token is read from the argument: do not paste real production tokens into shared shell history; use a short-lived one.
set -u
[ $# -ge 2 ] || { echo "usage: $0 <base-url> <bearer-token> [path]"; exit 2; }
BASE=${1%/}; TOKEN=$2; PATH_=${3:-/hbscript/WHOAMI}
echo "1. no token: expect 401"
curl -sk -o /dev/null -w "   HTTP %{http_code}\n" "$BASE$PATH_"
echo "2. garbage token: expect 401"
curl -sk -o /dev/null -w "   HTTP %{http_code}\n" -H "Authorization: Bearer not.a.token" "$BASE$PATH_"
echo "3. diagnostics (settings and the identity/PassTicket steps, HB.js not called): expect 200, step 2 = the mapped RACF ID, step 3 = generated"
curl -sk -H "Authorization: Bearer $TOKEN" "$BASE/_diag"
echo
echo "4. real call: expect 200 from HB.js, running as the mapped RACF ID"
curl -sk -w "\n   HTTP %{http_code}\n" -H "Authorization: Bearer $TOKEN" "$BASE$PATH_"
