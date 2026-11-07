#!/usr/bin/env bash
# RusTV installer / updater for Debian & Ubuntu (e.g. a Proxmox LXC container).
#
#   bash -c "$(curl -fsSL https://raw.githubusercontent.com/Renuzu/rustv/main/install.sh)"
#
# Run it again at any time to update to the latest release.
set -euo pipefail

REPO="Renuzu/rustv"
BIN=/usr/local/bin/rustv
ENV_FILE=/etc/rustv.env
UNIT=/etc/systemd/system/rustv.service

say() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "please run as root"
command -v systemctl >/dev/null || die "systemd is required"

case "$(uname -m)" in
  x86_64|amd64) ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *) die "unsupported CPU architecture: $(uname -m)" ;;
esac

if ! command -v curl >/dev/null; then
  say "Installing curl"
  apt-get update -q && apt-get install -y -q curl ca-certificates
fi

say "Downloading the latest RusTV release ($ARCH)"
TMP=$(mktemp)
curl -fL --progress-bar -o "$TMP" "https://github.com/$REPO/releases/latest/download/rustv-linux-$ARCH"
install -m 755 "$TMP" "$BIN"
rm -f "$TMP"

id rustv >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin rustv

if [ ! -f "$ENV_FILE" ]; then
  PW=""
  if [ -r /dev/tty ]; then
    read -r -p "Choose a password for the RusTV web interface (empty = random): " PW </dev/tty || true
  fi
  if [ -z "$PW" ]; then
    PW=$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 16)
    GENERATED=1
  fi
  cat >"$ENV_FILE" <<EOF
# RusTV settings – restart after changes: systemctl restart rustv
RUSTV_BIND=0.0.0.0:8080
RUSTV_PASSWORD=$PW
# RUSTV_USER_AGENT=VLC/3.0.20
EOF
  chmod 600 "$ENV_FILE"
fi

cat >"$UNIT" <<'EOF'
[Unit]
Description=RusTV IPTV player
After=network-online.target
Wants=network-online.target

[Service]
EnvironmentFile=/etc/rustv.env
ExecStart=/usr/local/bin/rustv
User=rustv
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable rustv >/dev/null 2>&1
systemctl restart rustv

IP=$(hostname -I 2>/dev/null | awk '{print $1}')
PORT=$(grep -E '^RUSTV_BIND=' "$ENV_FILE" | sed 's/.*://')
say "RusTV $("$BIN" --version 2>/dev/null || true) is running: http://${IP:-<this-host>}:${PORT:-8080}"
if [ "${GENERATED:-0}" = 1 ]; then
  say "Generated web password: $PW   (change it in $ENV_FILE)"
fi
say "Update later by running this script again."
