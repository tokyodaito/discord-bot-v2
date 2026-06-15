#!/usr/bin/env bash
set -euo pipefail

HOSTS="${DISCORD_BOT_HOSTS:-${SERVER_HOSTS:-147.182.178.32}}"
SSH_USER="${DISCORD_BOT_SSH_USER:-${SSH_USER:-root}}"
SSH_PORT="${DISCORD_BOT_SSH_PORT:-${SSH_PORT:-22}}"
APP_USER="${DISCORD_BOT_APP_USER:-discordbot}"
APP_DIR="${DISCORD_BOT_APP_DIR:-/opt/discordbot}"
SERVICE_NAME="${DISCORD_BOT_SERVICE:-discordbot}"
JAR_PATH="${DISCORD_BOT_JAR:-build/libs/DiscordBot-1.0.0-all.jar}"
JAVA_BIN="${DISCORD_BOT_JAVA_BIN:-/usr/bin/java}"
JAVA_OPTS="${DISCORD_BOT_JAVA_OPTS:--Xms32m -Xmx220m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC}"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found: $JAR_PATH" >&2
  exit 1
fi

ssh_opts=(-p "$SSH_PORT" -o BatchMode=yes -o StrictHostKeyChecking=accept-new)
scp_opts=(-P "$SSH_PORT" -o BatchMode=yes -o StrictHostKeyChecking=accept-new)

IFS=',' read -ra host_list <<< "$HOSTS"
for raw_host in "${host_list[@]}"; do
  host="$(echo "$raw_host" | xargs)"
  [[ -n "$host" ]] || continue

  target="$SSH_USER@$host"
  echo "Deploying $SERVICE_NAME to $target"

  ssh "${ssh_opts[@]}" "$target" \
    "id -u '$APP_USER' >/dev/null 2>&1 || useradd --system --home '$APP_DIR' --shell /usr/sbin/nologin '$APP_USER'; mkdir -p '$APP_DIR'; chown '$APP_USER:$APP_USER' '$APP_DIR'"

  scp "${scp_opts[@]}" "$JAR_PATH" "$target:$APP_DIR/bot.jar.tmp"

  ssh "${ssh_opts[@]}" "$target" \
    "mv '$APP_DIR/bot.jar.tmp' '$APP_DIR/bot.jar'; chown '$APP_USER:$APP_USER' '$APP_DIR/bot.jar'; chmod 0644 '$APP_DIR/bot.jar'"

  if [[ -n "${DISCORD_TOKEN:-}" ]]; then
    {
      printf 'DISCORD_TOKEN=%s\n' "$DISCORD_TOKEN"
      if [[ -n "${YOUTUBE_REFRESH_TOKEN:-}" ]]; then
        printf 'YOUTUBE_REFRESH_TOKEN=%s\n' "$YOUTUBE_REFRESH_TOKEN"
      fi
    } | ssh "${ssh_opts[@]}" "$target" \
      "umask 077; cat > '$APP_DIR/runtime.env.tmp'; chown '$APP_USER:$APP_USER' '$APP_DIR/runtime.env.tmp'; mv '$APP_DIR/runtime.env.tmp' '$APP_DIR/runtime.env'"
  else
    ssh "${ssh_opts[@]}" "$target" \
      "if [ ! -f '$APP_DIR/runtime.env' ]; then umask 077; printf 'DISCORD_TOKEN=\n' > '$APP_DIR/runtime.env'; chown '$APP_USER:$APP_USER' '$APP_DIR/runtime.env'; fi"
  fi

  ssh "${ssh_opts[@]}" "$target" "cat > /etc/systemd/system/$SERVICE_NAME.service" <<EOF
[Unit]
Description=DiscordBot
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$APP_USER
Group=$APP_USER
WorkingDirectory=$APP_DIR
EnvironmentFile=$APP_DIR/runtime.env
ExecStart=$JAVA_BIN $JAVA_OPTS -jar $APP_DIR/bot.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

  ssh "${ssh_opts[@]}" "$target" \
    "systemctl daemon-reload; systemctl enable '$SERVICE_NAME.service'; systemctl restart '$SERVICE_NAME.service'; systemctl --no-pager --full status '$SERVICE_NAME.service'"
done
