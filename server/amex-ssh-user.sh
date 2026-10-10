#!/usr/bin/env bash
# Tunnel-only SSH accounts for AMEX NET VPN. Run as root on your Linux server (Debian/Ubuntu style).
#   ./amex-ssh-user.sh add USER PASSWORD DAYS QUOTA_GB [MAX_LOGINS]   (QUOTA_GB 0 = unlimited)
#   ./amex-ssh-user.sh del USER
#   ./amex-ssh-user.sh list
#   ./amex-ssh-user.sh purge        (delete expired accounts; run daily from cron)
# What an account can do: port forwarding only. No shell, no terminal, no agent/X11 forwarding.
# It expires on a fixed date, can be capped in traffic and in simultaneous logins.
# Not tested on every distro: read it once before running it.
set -euo pipefail

GROUP="amex-tunnel"
die() { echo "error: $*" >&2; exit 1; }
[ "$(id -u)" -eq 0 ] || die "run as root"

reload_sshd() {
  sshd -t || die "sshd config test failed, not reloading"
  systemctl reload ssh 2>/dev/null || systemctl reload sshd
}

setup_sshd() {
  groupadd -f "$GROUP"
  local conf="/etc/ssh/sshd_config.d/99-amex.conf"
  if [ -d /etc/ssh/sshd_config.d ] && grep -qiE '^\s*Include\s+/etc/ssh/sshd_config.d' /etc/ssh/sshd_config; then
    :
  else
    conf="/etc/ssh/sshd_config"
  fi
  if ! grep -q "Match Group $GROUP" "$conf" 2>/dev/null; then
    cat >> "$conf" <<CONF

Match Group $GROUP
    AllowTcpForwarding yes
    PermitTTY no
    X11Forwarding no
    AllowAgentForwarding no
    PermitTunnel no
    GatewayPorts no
    ForceCommand /usr/sbin/nologin
CONF
    reload_sshd
  fi
}

add() {
  local u="${1:-}" pw="${2:-}" days="${3:-}" gb="${4:-}" maxl="${5:-2}"
  [[ "$u" =~ ^[a-z_][a-z0-9_-]{2,30}$ ]] || die "username: 3-31 chars, lowercase letters, digits, _ or -"
  [ "${#pw}" -ge 8 ] || die "password must be at least 8 characters"
  [[ "$days" =~ ^[0-9]+$ ]] && [ "$days" -ge 1 ] || die "DAYS must be a number >= 1"
  [[ "$gb" =~ ^[0-9]+$ ]] || die "QUOTA_GB must be a number (0 = unlimited)"
  id "$u" >/dev/null 2>&1 && die "user $u already exists"
  setup_sshd
  local expiry
  expiry="$(date -d "+$days days" +%F)"
  useradd -M -s /usr/sbin/nologin -G "$GROUP" -e "$expiry" "$u"
  echo "$u:$pw" | chpasswd
  mkdir -p /etc/security/limits.d
  echo "$u hard maxlogins $maxl" >> /etc/security/limits.d/amex.conf
  if [ "$gb" -gt 0 ]; then
    local uid bytes
    uid="$(id -u "$u")"
    bytes=$((gb * 1024 * 1024 * 1024))
    iptables -N AMEXQ 2>/dev/null || true
    iptables -C OUTPUT -j AMEXQ 2>/dev/null || iptables -I OUTPUT 1 -j AMEXQ
    iptables -A AMEXQ -m owner --uid-owner "$uid" -m quota --quota "$bytes" -j RETURN
    iptables -A AMEXQ -m owner --uid-owner "$uid" -j REJECT
    echo "note: iptables rules are not persistent across reboots (use iptables-persistent), and the quota counts bytes the server sends for this user (both directions of the tunnel), so real usage is roughly half the cap."
  fi
  echo "created $u, expires $expiry, max logins $maxl, quota ${gb} GB"
  echo "Put the same date ($(date -d "$expiry" +%d/%m/%Y)) in the profile's Notes & message so the app expires it too."
}

del() {
  local u="${1:-}"
  id "$u" >/dev/null 2>&1 || die "no such user"
  id -nG "$u" | tr ' ' '\n' | grep -qx "$GROUP" || die "$u is not an AMEX account"
  local uid
  uid="$(id -u "$u")"
  pkill -KILL -u "$u" 2>/dev/null || true
  while iptables -D AMEXQ -m owner --uid-owner "$uid" -j REJECT 2>/dev/null; do :; done
  iptables -S AMEXQ 2>/dev/null | grep -- "--uid-owner $uid " | sed 's/^-A/-D/' | while read -r r; do iptables $r || true; done
  sed -i "/^$u hard maxlogins/d" /etc/security/limits.d/amex.conf 2>/dev/null || true
  userdel "$u"
  echo "deleted $u"
}

members() { getent group "$GROUP" | cut -d: -f4 | tr ',' ' '; }

list() {
  for u in $(members); do
    printf '%-20s expires: %s\n' "$u" "$(chage -l "$u" | awk -F': ' '/Account expires/{print $2}')"
  done
}

purge() {
  local now
  now="$(date +%s)"
  for u in $(members); do
    local e
    e="$(chage -l "$u" | awk -F': ' '/Account expires/{print $2}')"
    [ "$e" = "never" ] && continue
    if [ "$(date -d "$e" +%s)" -lt "$now" ]; then del "$u"; fi
  done
}

case "${1:-}" in
  add) shift; add "$@" ;;
  del) shift; del "$@" ;;
  list) list ;;
  purge) purge ;;
  *) sed -n '2,9p' "$0"; exit 1 ;;
esac
