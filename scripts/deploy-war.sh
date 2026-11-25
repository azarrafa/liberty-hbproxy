#!/bin/bash
# Uploads liberty-app/hbproxy.war to a z/OS host with Zowe CLI (z/OSMF files API), in binary mode.
#
#   scripts/deploy-war.sh <profile> <path> [extra zowe options, for example --port 1443]
#     profile  Zowe z/OSMF profile of the target system (it holds host, port, user and password), see: zowe config list / zowe profiles
#     path     absolute USS directory to put the war in, for example <WORK_DIR>/<APPLID>/<JVMSERVER>/wlp/usr/servers/defaultServer/apps
#
# Needs: Zowe CLI (npm i -g @zowe/cli) and a z/OSMF profile for the host. No credentials are handled by this script.
# Env: WAR (default liberty-app/hbproxy.war).
# Liberty reloads an application when its war changes: check messages.log for CWWKZ0003I (updated) or CWWKZ0002E (failed).
set -euo pipefail

[ $# -ge 2 ] || { echo "usage: $0 <profile> <path> [extra zowe options]"; exit 2; }
PROFILE=$1
DEST=${2%/}
shift 2
case "$DEST" in /*) ;; *) echo "path must be absolute (start with /): $DEST"; exit 2;; esac

HERE=$(cd "$(dirname "$0")" && pwd)
WAR=${WAR:-$(dirname "$HERE")/liberty-app/hbproxy.war}
command -v zowe >/dev/null || { echo "zowe CLI not found: npm i -g @zowe/cli"; exit 1; }
[ -s "$WAR" ] || { echo "missing or empty: $WAR (build it with liberty-app/build.sh)"; exit 1; }

zowe zos-files upload file-to-uss "$WAR" "$DEST/$(basename "$WAR")" --binary --zosmf-profile "$PROFILE" "$@"
echo "uploaded $(basename "$WAR") ($(wc -c < "$WAR" | tr -d ' ') bytes) to $PROFILE:$DEST"
