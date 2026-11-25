#!/bin/sh
# Build hbproxy.war.
#   Workstation:  SERVLET_API=/path/to/javax.servlet-api-3.0.1.jar ./build.sh      (any servlet 3.0/3.1 API jar, compile only)
#   On z/OS USS:  ./build.sh     (uses the servlet jar shipped with Liberty, see WLP_INSTALL_DIR below)
# Needs a JDK 11 or later (javac and jar). The war is written to ./hbproxy.war.
# IRRRacf.jar is NOT needed to compile: the servlet reaches IRRPassTicket by reflection. It is needed at run time (see config/).
set -e
cd "$(dirname "$0")"
if [ -z "${SERVLET_API:-}" ] && [ -n "${WLP_INSTALL_DIR:-}" ]; then
  SERVLET_API=$(ls "$WLP_INSTALL_DIR"/dev/api/spec/com.ibm.websphere.javaee.servlet.3.0_*.jar 2>/dev/null | head -1)
fi
: "${SERVLET_API:?set SERVLET_API to a servlet API jar, or WLP_INSTALL_DIR to the Liberty install directory}"
[ -f "$SERVLET_API" ] || { echo "not found: $SERVLET_API"; exit 1; }
rm -rf build hbproxy.war && mkdir -p build/WEB-INF/classes
javac --release 11 -encoding UTF-8 -cp "$SERVLET_API" -d build/WEB-INF/classes src/hbproxy/*.java
cp src/WEB-INF/web.xml build/WEB-INF/web.xml
(cd build && jar cf ../hbproxy.war .)
echo "built $(pwd)/hbproxy.war"
