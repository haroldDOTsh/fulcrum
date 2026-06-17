#!/usr/bin/env sh
set -eu

: "${FULCRUM_PROFILE:=single-machine}"
: "${FULCRUM_PROBE_HOST:=0.0.0.0}"
: "${FULCRUM_PROBE_PORT:=8080}"
: "${VELOCITY_SERVER_JAR:=/opt/fulcrum/velocity/velocity-server.jar}"
: "${VELOCITY_JAVA_OPTS:=-Xms256m -Xmx1g}"
: "${VELOCITY_ARGS:=}"

cd /opt/fulcrum/velocity

if [ "$#" -eq 0 ]; then
  set -- \
    --profile="${FULCRUM_PROFILE}" \
    --role=velocity-agent \
    --mode=run \
    --probe-host="${FULCRUM_PROBE_HOST}" \
    --probe-port="${FULCRUM_PROBE_PORT}"
fi

/opt/fulcrum/fulcrum/bin/fulcrum "$@" &
FULCRUM_PID="$!"

terminate_fulcrum() {
  kill "${FULCRUM_PID}" 2>/dev/null || true
}

trap terminate_fulcrum INT TERM EXIT

exec java ${VELOCITY_JAVA_OPTS} -jar "${VELOCITY_SERVER_JAR}" ${VELOCITY_ARGS}
