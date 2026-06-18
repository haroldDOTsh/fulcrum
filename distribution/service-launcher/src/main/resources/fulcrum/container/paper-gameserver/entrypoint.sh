#!/usr/bin/env sh
set -eu

: "${FULCRUM_PROFILE:=single-machine}"
: "${FULCRUM_PROBE_HOST:=0.0.0.0}"
: "${FULCRUM_PROBE_PORT:=18081}"
: "${PAPER_SERVER_JAR:=/opt/fulcrum/paper/paper-server.jar}"
: "${PAPER_JAVA_OPTS:=-Xms512m -Xmx2g}"
: "${PAPER_ARGS:=nogui}"
: "${PAPER_SERVER_PORT:=25565}"
: "${PAPER_ONLINE_MODE:=false}"
: "${PAPER_ENFORCE_SECURE_PROFILE:=false}"

cd /opt/fulcrum/paper

if [ "${MINECRAFT_EULA:-false}" = "true" ]; then
  printf 'eula=true\n' > eula.txt
fi

cat > server.properties <<EOF
server-port=${PAPER_SERVER_PORT}
online-mode=${PAPER_ONLINE_MODE}
enforce-secure-profile=${PAPER_ENFORCE_SECURE_PROFILE}
motd=Fulcrum Lobby
enable-status=false
prevent-proxy-connections=false
EOF

if [ "$#" -eq 0 ]; then
  set -- \
    --profile="${FULCRUM_PROFILE}" \
    --role=paper-agent \
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

exec java ${PAPER_JAVA_OPTS} -jar "${PAPER_SERVER_JAR}" ${PAPER_ARGS}
