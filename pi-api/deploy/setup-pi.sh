#!/usr/bin/env bash
# Idempotent installer for the PiHouse local API and the audio stack it depends on.
#
# Run on the target Raspberry Pi (Raspberry Pi OS Lite 64-bit / Bookworm) as the
# pi user (or a user that is a sudoer; the script asks for sudo when needed):
#
#   cd ~/pihouse/pi-api
#   ./deploy/setup-pi.sh
#
# Re-running the script is safe: every step checks current state before changing
# anything. The script never touches a paired/trusted Bluetooth speaker (that
# flow runs from the Android app via /operations/pair-speaker and
# /operations/assign-speaker).
#
# After this script finishes the API runs in adapterMode=real and
# GET /api/v1/status returns "adapterMode": "real".

set -euo pipefail

log() { printf '[setup-pi] %s\n' "$*"; }
warn() { printf '[setup-pi] WARN: %s\n' "$*" >&2; }
die() { printf '[setup-pi] ERROR: %s\n' "$*" >&2; exit 1; }

# --- 0. Locate the repo ------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PI_API_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${PI_API_DIR}/.." && pwd)"

TARGET_USER="${SUDO_USER:-$(id -un)}"
TARGET_HOME="$(getent passwd "${TARGET_USER}" | cut -d: -f6)"
[[ -n "${TARGET_HOME}" ]] || die "could not resolve home directory for ${TARGET_USER}"

CONFIG_DIR="${TARGET_HOME}/.config/pihouse-api"
ACTIVE_CONFIG="${CONFIG_DIR}/config.json"
TOKEN_FILE="${CONFIG_DIR}/token"
USER_SYSTEMD_DIR="${TARGET_HOME}/.config/systemd/user"
LIBRESPOT_CFG_DIR="${TARGET_HOME}/.config/librespot"
PIPEWIRE_CONF_DIR="${TARGET_HOME}/.config/pipewire/pipewire.conf.d"
BIN_DIR="${TARGET_HOME}/bin"

log "target user: ${TARGET_USER}"
log "target home: ${TARGET_HOME}"
log "pi-api dir : ${PI_API_DIR}"

# --- 1. APT packages ---------------------------------------------------------
APT_PACKAGES=(
  pipewire
  pipewire-pulse
  wireplumber
  libspa-0.2-bluetooth
  pulseaudio-utils
  bluez
  python3-venv
  python3-pip
  curl
  ca-certificates
)

install_apt_packages() {
  local missing=()
  for pkg in "${APT_PACKAGES[@]}"; do
    if ! dpkg -s "${pkg}" >/dev/null 2>&1; then
      missing+=("${pkg}")
    fi
  done
  if [[ ${#missing[@]} -eq 0 ]]; then
    log "apt packages already installed"
    return
  fi
  log "installing apt packages: ${missing[*]}"
  sudo apt-get update -y
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y "${missing[@]}"
}

# --- 2. librespot via Raspotify ---------------------------------------------
install_librespot() {
  if command -v librespot >/dev/null 2>&1; then
    log "librespot already on PATH ($(command -v librespot))"
  else
    log "installing librespot via Raspotify"
    curl -sL https://dtcooper.github.io/raspotify/install.sh | sh
  fi
  # The Raspotify-managed service competes with our per-endpoint units.
  if systemctl list-unit-files | grep -q '^raspotify\.service'; then
    if systemctl is-enabled --quiet raspotify 2>/dev/null || systemctl is-active --quiet raspotify 2>/dev/null; then
      log "disabling default raspotify.service (we run librespot@<endpoint> units instead)"
      sudo systemctl disable --now raspotify || true
    fi
  fi
}

# --- 3. Enable user-service linger ------------------------------------------
enable_linger() {
  if loginctl show-user "${TARGET_USER}" 2>/dev/null | grep -q '^Linger=yes'; then
    log "linger already enabled for ${TARGET_USER}"
  else
    log "enabling linger for ${TARGET_USER}"
    sudo loginctl enable-linger "${TARGET_USER}"
  fi
}

# --- 4. PipeWire combine sink (whole_house) ---------------------------------
COMBINE_CONF_PATH="${PIPEWIRE_CONF_DIR}/combine.conf"
COMBINE_CONF_BODY='context.modules = [
  { name = libpipewire-module-combine-stream
    args = {
      combine.mode = sink
      node.name = "whole_house"
      node.description = "Whole House"
      combine.latency-compensate = true
      combine.props = { audio.position = [ FL FR ] }
      stream.rules = [
        { matches = [ { media.class = "Audio/Sink" node.name ~ "^bluez_output\\." } ]
          actions = { create-stream = { } } }
      ]
    }
  }
]
'

install_combine_sink() {
  mkdir -p "${PIPEWIRE_CONF_DIR}"
  if [[ -f "${COMBINE_CONF_PATH}" ]] && diff -q <(printf '%s' "${COMBINE_CONF_BODY}") "${COMBINE_CONF_PATH}" >/dev/null 2>&1; then
    log "combine.conf already in place"
    return
  fi
  log "writing ${COMBINE_CONF_PATH}"
  printf '%s' "${COMBINE_CONF_BODY}" > "${COMBINE_CONF_PATH}"
}

# --- 5. librespot@.service template + env files -----------------------------
LIBRESPOT_TEMPLATE_PATH="${USER_SYSTEMD_DIR}/librespot@.service"
LIBRESPOT_TEMPLATE_BODY='[Unit]
Description=librespot (%i)
After=pipewire.service network-online.target

[Service]
EnvironmentFile=%h/.config/librespot/%i.env
ExecStart=/usr/bin/librespot --backend pulseaudio --name "${SPEAKER_NAME}" --bitrate 320 --disable-audio-cache
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
'

install_librespot_units() {
  mkdir -p "${USER_SYSTEMD_DIR}" "${LIBRESPOT_CFG_DIR}"
  if [[ -f "${LIBRESPOT_TEMPLATE_PATH}" ]] && diff -q <(printf '%s' "${LIBRESPOT_TEMPLATE_BODY}") "${LIBRESPOT_TEMPLATE_PATH}" >/dev/null 2>&1; then
    log "librespot@.service already in place"
  else
    log "writing ${LIBRESPOT_TEMPLATE_PATH}"
    printf '%s' "${LIBRESPOT_TEMPLATE_BODY}" > "${LIBRESPOT_TEMPLATE_PATH}"
  fi
  # Seed the per-endpoint env files only if they do not already exist. The real
  # adapter rewrites these whenever the app assigns a speaker, so we never
  # clobber an operator's MAC-derived sink names here.
  for endpoint in indoor outdoor both; do
    local env_path="${LIBRESPOT_CFG_DIR}/${endpoint}.env"
    if [[ -f "${env_path}" ]]; then
      continue
    fi
    log "seeding ${env_path}"
    case "${endpoint}" in
      indoor)  printf 'SPEAKER_NAME=Indoor\nPULSE_SINK=\n'      > "${env_path}" ;;
      outdoor) printf 'SPEAKER_NAME=Outdoor\nPULSE_SINK=\n'     > "${env_path}" ;;
      both)    printf 'SPEAKER_NAME=Whole House\nPULSE_SINK=whole_house\n' > "${env_path}" ;;
    esac
  done
}

# --- 6. bt-watchdog (script + service + timer) ------------------------------
WATCHDOG_SCRIPT_PATH="${BIN_DIR}/bt-watchdog.sh"
WATCHDOG_SCRIPT_BODY='#!/usr/bin/env bash
# Reconnect trusted Bluetooth speakers if they have dropped.
# The list of trusted addresses is read from BlueZ so no MACs are hard-coded.
set -euo pipefail
mapfile -t devices < <(bluetoothctl devices Trusted 2>/dev/null | awk "{print \$2}")
for mac in "${devices[@]}"; do
  [[ -n "${mac}" ]] || continue
  if ! bluetoothctl info "${mac}" 2>/dev/null | grep -q "Connected: yes"; then
    bluetoothctl connect "${mac}" >/dev/null 2>&1 || true
  fi
done
'

WATCHDOG_SERVICE_PATH="${USER_SYSTEMD_DIR}/bt-watchdog.service"
WATCHDOG_SERVICE_BODY='[Unit]
Description=Reconnect trusted Bluetooth speakers

[Service]
Type=oneshot
ExecStart=%h/bin/bt-watchdog.sh
'

WATCHDOG_TIMER_PATH="${USER_SYSTEMD_DIR}/bt-watchdog.timer"
WATCHDOG_TIMER_BODY='[Unit]
Description=Run Bluetooth reconnect watchdog every minute

[Timer]
OnBootSec=30
OnUnitActiveSec=60
Unit=bt-watchdog.service

[Install]
WantedBy=timers.target
'

install_watchdog() {
  mkdir -p "${BIN_DIR}" "${USER_SYSTEMD_DIR}"
  if [[ -f "${WATCHDOG_SCRIPT_PATH}" ]] && diff -q <(printf '%s' "${WATCHDOG_SCRIPT_BODY}") "${WATCHDOG_SCRIPT_PATH}" >/dev/null 2>&1; then
    log "bt-watchdog.sh already in place"
  else
    log "writing ${WATCHDOG_SCRIPT_PATH}"
    printf '%s' "${WATCHDOG_SCRIPT_BODY}" > "${WATCHDOG_SCRIPT_PATH}"
    chmod +x "${WATCHDOG_SCRIPT_PATH}"
  fi
  if [[ -f "${WATCHDOG_SERVICE_PATH}" ]] && diff -q <(printf '%s' "${WATCHDOG_SERVICE_BODY}") "${WATCHDOG_SERVICE_PATH}" >/dev/null 2>&1; then
    log "bt-watchdog.service already in place"
  else
    log "writing ${WATCHDOG_SERVICE_PATH}"
    printf '%s' "${WATCHDOG_SERVICE_BODY}" > "${WATCHDOG_SERVICE_PATH}"
  fi
  if [[ -f "${WATCHDOG_TIMER_PATH}" ]] && diff -q <(printf '%s' "${WATCHDOG_TIMER_BODY}") "${WATCHDOG_TIMER_PATH}" >/dev/null 2>&1; then
    log "bt-watchdog.timer already in place"
  else
    log "writing ${WATCHDOG_TIMER_PATH}"
    printf '%s' "${WATCHDOG_TIMER_BODY}" > "${WATCHDOG_TIMER_PATH}"
  fi
}

# --- 7. API service config + token ------------------------------------------
install_active_config() {
  mkdir -p "${CONFIG_DIR}"
  if [[ -f "${ACTIVE_CONFIG}" ]]; then
    log "active config already present at ${ACTIVE_CONFIG} (leaving operator edits in place)"
  else
    log "installing config.real.example.json -> ${ACTIVE_CONFIG} (adapterMode=real)"
    cp "${PI_API_DIR}/config.real.example.json" "${ACTIVE_CONFIG}"
  fi
  if [[ -f "${TOKEN_FILE}" ]] && [[ -s "${TOKEN_FILE}" ]]; then
    log "bearer token already present at ${TOKEN_FILE}"
  else
    log "generating bearer token -> ${TOKEN_FILE}"
    python3 -c "import secrets; print(secrets.token_urlsafe(32))" > "${TOKEN_FILE}"
  fi
  chmod 600 "${TOKEN_FILE}"
}

# --- 8. Python venv + install -----------------------------------------------
install_python_env() {
  local venv="${PI_API_DIR}/.venv"
  if [[ ! -x "${venv}/bin/python" ]]; then
    log "creating venv at ${venv}"
    python3 -m venv "${venv}"
  else
    log "venv already present at ${venv}"
  fi
  log "installing pihouse_api into venv (editable)"
  "${venv}/bin/python" -m pip install --quiet --upgrade pip
  "${venv}/bin/python" -m pip install --quiet -e "${PI_API_DIR}"
}

# --- 9. pihouse-api.service --------------------------------------------------
API_SERVICE_PATH="${USER_SYSTEMD_DIR}/pihouse-api.service"

install_api_service() {
  mkdir -p "${USER_SYSTEMD_DIR}"
  if [[ -f "${API_SERVICE_PATH}" ]] && diff -q "${PI_API_DIR}/deploy/pihouse-api.service" "${API_SERVICE_PATH}" >/dev/null 2>&1; then
    log "pihouse-api.service already in place"
  else
    log "installing pihouse-api.service -> ${API_SERVICE_PATH}"
    cp "${PI_API_DIR}/deploy/pihouse-api.service" "${API_SERVICE_PATH}"
  fi
}

# --- 10. systemd reload + enable + start ------------------------------------
start_user_units() {
  log "reloading user systemd"
  systemctl --user daemon-reload
  for unit in bt-watchdog.timer pihouse-api.service; do
    if systemctl --user is-enabled --quiet "${unit}" 2>/dev/null; then
      log "${unit} already enabled"
    else
      log "enabling ${unit}"
      systemctl --user enable "${unit}"
    fi
    log "(re)starting ${unit}"
    systemctl --user restart "${unit}"
  done
}

# --- main --------------------------------------------------------------------
install_apt_packages
install_librespot
enable_linger
install_combine_sink
install_librespot_units
install_watchdog
install_active_config
install_python_env
install_api_service
start_user_units

HOST_NAME="$(hostname 2>/dev/null || true)"
PRIMARY_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
TOKEN_VALUE="$(tr -d '\r\n' < "${TOKEN_FILE}" 2>/dev/null || true)"

cat <<EOF
[setup-pi] Done.

================================================================================
Mobile connection info (paste/scan into the Android app):

  host (mDNS) : ${HOST_NAME:-<set hostname>}.local
  host (IP)   : ${PRIMARY_IP:-<no IPv4 detected>}
  port        : 8765
  bearer      : ${TOKEN_VALUE:-<token file missing>}

Token file on disk : ${TOKEN_FILE}
Active config      : ${ACTIVE_CONFIG} (adapterMode=real)
================================================================================

Next steps that the script intentionally does NOT do (they require physical
hardware or operator decisions):
  * Pair and trust the two Bluetooth speakers (run the Android app's pair flow,
    or use 'bluetoothctl').
  * Fill in real PipeWire sink names in ${ACTIVE_CONFIG} if you do not want to
    rely on the app-driven assignment flow rewriting them.
  * Choose which speaker is "Indoor" vs "Outdoor" via the app's assign flow.

Smoke checks:
  curl http://127.0.0.1:8765/api/v1/identity
  curl http://127.0.0.1:8765/api/v1/health
  curl -H "Authorization: Bearer \$(cat ${TOKEN_FILE})" \\
       http://127.0.0.1:8765/api/v1/status | python3 -c "import json,sys; b=json.load(sys.stdin); print('adapterMode=', b.get('adapterMode'))"

Preflight (re-run any time to find what is missing on this Pi):
  ${PI_API_DIR}/.venv/bin/python -m pihouse_api.bootstrap

The status payload now exposes a top-level "adapterMode" field. If it ever
prints "stub" on this Pi, the active config at ${ACTIVE_CONFIG} is not the real
one and the API is masquerading -- fix the config and restart pihouse-api.
EOF
