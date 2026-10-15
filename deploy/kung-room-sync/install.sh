#!/usr/bin/env bash
# Kung server installer and migration. Run as root from the folder that holds
# kung-room-sync-server.mjs; that folder is the install and can be anywhere (no spaces):
#   cd <folder> && bash install.sh [--yes]
# Safe to run twice. Moves an old install (/opt/kung-server, /etc/kung-server.env,
# /var/lib/kung-server) into this folder. The systemd unit is generated here on every run.
# Old files are only removed after the new service passed every check, one exact file at a
# time (never rm -rf), and only after you confirm (--yes skips the question). A failure after
# the old service was stopped rolls back.
set -euo pipefail

# Test knobs. Nothing else is overridable.
ROOT=${KUNG_ROOT:-$(cd "$(dirname "$0")" && pwd -P)}
ETC=${KUNG_ETC:-/etc}
OPT=${KUNG_OPT:-/opt}
VARLIB=${KUNG_VARLIB:-/var/lib}

UNIT=kung-server
NEW_ENV=$ROOT/kung-server.env
NEW_UNIT=$ROOT/kung-server.service
NEW_SERVER=$ROOT/kung-room-sync-server.mjs
NEW_DATA_DIR=$ROOT/data
NEW_DATA_FILE=$NEW_DATA_DIR/known-rooms.json
UNIT_LINK=$ETC/systemd/system/$UNIT.service
DROPIN=$ETC/systemd/system/$UNIT.service.d/host.conf
ASIDE=.pre-kung-migrate
ASSUME_YES=0

for arg in "$@"; do
  case $arg in
    -y|--yes) ASSUME_YES=1 ;;
    *) echo "usage: bash $0 [--yes]" >&2; exit 2 ;;
  esac
done

die() { echo "ERROR: $*" >&2; exit 1; }
say() { echo "== $*"; }

# env_get FILE KEY: value of the last KEY= line (CR and surrounding quotes stripped).
env_get() {
  { grep -E "^[[:space:]]*$2=" "$1" 2>/dev/null || true; } | tail -n 1 | cut -d= -f2- | tr -d '\r' \
    | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'\$/\1/"
}

# env_set FILE KEY VALUE: replace the KEY= line or append it; untouched when already equal.
# VALUE must not contain | & or \.
env_set() {
  [ "$(env_get "$1" "$2")" != "$3" ] || return 0
  if grep -q "^$2=" "$1"; then
    sed -i "s|^$2=.*|$2=$3|" "$1"
  else
    if [ -n "$(tail -c1 "$1")" ]; then echo >> "$1"; fi
    echo "$2=$3" >> "$1"
  fi
}

# env_covered OLD NEW: every KEY=VALUE of OLD (except the data path, and the bind address when it
# came from the drop-in) has the same value in NEW. Prints key names of differences, never values.
env_covered() {
  local line key rc=0
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    line=${line#"${line%%[![:space:]]*}"}
    case $line in [A-Za-z_]*=*) ;; *) continue ;; esac
    key=${line%%=*}
    if [ "$key" = KUNG_ROOM_DATA_PATH ]; then continue; fi
    if [ "$key" = KUNG_ROOM_SYNC_HOST ] && [ -n "$HOST_FROM_DROPIN" ]; then continue; fi
    if [ "$(env_get "$1" "$key")" != "$(env_get "$2" "$key")" ]; then
      echo "  not identical in the new env file: $key"
      rc=1
    fi
  done < "$1"
  return $rc
}

# safe_path P: absolute, and only characters that need no quoting in a unit file or sed.
safe_path() {
  case $1 in /*) ;; *) return 1 ;; esac
  case $1 in *[!A-Za-z0-9/._+@:-]*) return 1 ;; esac
  return 0
}

# in_root P: P is the install folder, inside it, or resolves into it.
in_root() {
  local r
  case $1 in "$ROOT"|"$ROOT"/*) return 0 ;; esac
  r=$(readlink -f -- "$1" 2>/dev/null || echo "$1")
  case $r in "$ROOT_REAL"|"$ROOT_REAL"/*) return 0 ;; esac
  return 1
}

# rm_file P: exact file or symlink only. Never a directory, never anything in $ROOT. No -r.
rm_file() {
  local p=$1
  case $p in /*) ;; *) echo "refusing to delete '$p': not an absolute path" >&2; return 1 ;; esac
  if [ -d "$p" ] && [ ! -L "$p" ]; then echo "refusing to delete '$p': is a directory" >&2; return 1; fi
  if in_root "$p"; then echo "refusing to delete '$p': inside $ROOT" >&2; return 1; fi
  rm -f -- "$p"
}

# rmdir_if_empty D: rmdir without -r; a symlink is only removed once its target is gone.
rmdir_if_empty() {
  local d=$1
  if in_root "$d"; then return 0; fi
  if [ -L "$d" ]; then
    if [ ! -e "$d" ]; then rm_file "$d" || true; fi
  elif [ -d "$d" ]; then
    rmdir -- "$d" 2>/dev/null || true
  fi
  return 0
}

# --- rollback ------------------------------------------------------------------------------
ARMED=0; WAS_ACTIVE=0; WAS_ENABLED=0; ENABLED_BY_US=0
DROPIN_MOVED=0; UNIT_MOVED=0; LINKED=0; OLD_LINK_TARGET=""

rollback() {
  [ "$ARMED" -eq 1 ] || return 0
  ARMED=0
  set +e
  echo "ROLLBACK: restoring the old setup (best effort)" >&2
  systemctl stop "$UNIT" >/dev/null 2>&1
  if [ "$ENABLED_BY_US" -eq 1 ] && [ "$WAS_ENABLED" -eq 0 ]; then
    systemctl disable "$UNIT" >/dev/null 2>&1 && echo "  - disabled $UNIT again"
  fi
  if [ "$LINKED" -eq 1 ] && [ -L "$UNIT_LINK" ]; then
    rm -f -- "$UNIT_LINK" && echo "  - removed the link $UNIT_LINK"
  fi
  if [ "$UNIT_MOVED" -eq 1 ] && [ -e "$UNIT_LINK$ASIDE" ]; then
    mv -f -- "$UNIT_LINK$ASIDE" "$UNIT_LINK" && echo "  - restored $UNIT_LINK"
  fi
  if [ -n "$OLD_LINK_TARGET" ] && [ ! -e "$UNIT_LINK" ] && [ ! -L "$UNIT_LINK" ]; then
    ln -s -- "$OLD_LINK_TARGET" "$UNIT_LINK" && echo "  - restored the old link $UNIT_LINK"
  fi
  if [ "$DROPIN_MOVED" -eq 1 ] && [ -e "$DROPIN$ASIDE" ]; then
    mv -f -- "$DROPIN$ASIDE" "$DROPIN" && echo "  - restored $DROPIN"
  fi
  systemctl daemon-reload && echo "  - systemd reloaded"
  if [ "$WAS_ACTIVE" -eq 1 ]; then
    systemctl start "$UNIT" && echo "  - started $UNIT again (it was running before)"
  fi
  echo "NOTHING was deleted. Fix the cause above and run install.sh again."
}

on_exit() {
  local rc=$?
  trap - EXIT
  rm -f -- "$NEW_DATA_FILE.tmp" 2>/dev/null || true
  if [ "$rc" -ne 0 ]; then rollback || true; fi
  exit "$rc"
}
trap on_exit EXIT
trap 'exit 130' INT TERM HUP

# --- 1. checks (nothing is changed yet) ----------------------------------------------------
[ "$(id -u)" -eq 0 ] || die "must run as root: bash $ROOT/install.sh"
command -v systemctl >/dev/null || die "systemctl not found (systemd required)"
safe_path "$ROOT" || die "the folder '$ROOT' must be an absolute path of letters, digits and / . _ + @ : - only (no spaces); move the files to a simpler folder"
[ -f "$NEW_SERVER" ] || die "missing $NEW_SERVER (install.sh must sit next to kung-room-sync-server.mjs)"
[ ! -L "$ROOT" ] || die "$ROOT is a symlink"
[ ! -L "$NEW_UNIT" ] || die "$NEW_UNIT is a symlink"
[ ! -L "$NEW_DATA_DIR" ] || die "$NEW_DATA_DIR is a symlink"
[ ! -L "$NEW_DATA_FILE" ] || die "$NEW_DATA_FILE is a symlink"
if command -v mountpoint >/dev/null 2>&1; then
  if mountpoint -q -- "$ROOT"; then die "$ROOT is a mount point"; fi
  if [ -d "$NEW_DATA_DIR" ] && mountpoint -q -- "$NEW_DATA_DIR"; then die "$NEW_DATA_DIR is a mount point"; fi
fi
ROOT_REAL=$(readlink -f -- "$ROOT")

# The generated unit starts this node; the health check uses the same one.
NODE=$(command -v node || true)
if ! safe_path "$NODE" || [ ! -x "$NODE" ]; then
  die "node not found at an absolute path of plain characters (found '${NODE:-nothing}'); install Node.js 18 or newer"
fi
node_v=$("$NODE" -v)
node_major=${node_v#v}; node_major=${node_major%%.*}
[ "$node_major" -ge 18 ] 2>/dev/null || die "node $node_v is too old, need 18 or newer"

# --- 2. discover the old installation (systemd first, then the default paths) --------------
say "Looking for an old installation"
unit_text=""; sd_env=""; sd_exec=""; old_wd=""
if systemctl cat "$UNIT" >/dev/null 2>&1; then
  unit_text=$(systemctl cat "$UNIT")
  sd_env=$(printf '%s\n' "$unit_text" | sed -nE 's/^[[:space:]]*EnvironmentFile=-?//p' | tail -n 1)
  old_wd=$(printf '%s\n' "$unit_text" | sed -nE 's/^[[:space:]]*WorkingDirectory=-?//p' | tail -n 1)
  sd_exec=$(printf '%s\n' "$unit_text" | grep '^[[:space:]]*ExecStart=' \
    | grep -o '[^[:space:]=]*kung-room-sync-server\.mjs' | tail -n 1 || true)
fi

OLD_ENV=""
for c in "$sd_env" "$ETC/kung-server.env"; do
  if [ -n "$c" ] && [ "$c" != "$NEW_ENV" ] && [ -f "$c" ]; then OLD_ENV=$c; break; fi
done

cand_dirs=("$OPT/kung-server")
if [ -n "$sd_exec" ]; then cand_dirs=("$(dirname -- "$sd_exec")" "$OPT/kung-server"); fi
OLD_DIRS=()
for d in "${cand_dirs[@]}"; do
  # this folder being the old location (new server uploaded over the old one) is "already in place"
  if [ "$(readlink -f -- "$d" 2>/dev/null || echo "$d")" = "$ROOT_REAL" ] || [ ! -f "$d/kung-room-sync-server.mjs" ]; then continue; fi
  case " ${OLD_DIRS[*]:-} " in *" $d "*) continue ;; esac
  OLD_DIRS+=("$d")
done

OLD_DATA_FILE=""
if [ -n "$OLD_ENV" ]; then OLD_DATA_FILE=$(env_get "$OLD_ENV" KUNG_ROOM_DATA_PATH); fi
case $OLD_DATA_FILE in
  /*) ;;
  '') OLD_DATA_FILE=$VARLIB/kung-server/known-rooms.json ;;
  *) OLD_DATA_FILE=${old_wd%/}/$OLD_DATA_FILE ;;   # relative: against the old service's cwd
esac
if [ ! -e "$OLD_DATA_FILE" ]; then
  for d in "${OLD_DIRS[@]:-}" "$ROOT"; do   # server started by hand from its own folder
    if [ -n "$d" ] && [ -f "$d/room-sync-data/known-rooms.json" ]; then
      OLD_DATA_FILE=$d/room-sync-data/known-rooms.json; break
    fi
  done
fi
if [ "$OLD_DATA_FILE" = "$NEW_DATA_FILE" ]; then OLD_DATA_FILE=""; fi

echo "old env file : ${OLD_ENV:-none}"
echo "old server   : ${OLD_DIRS[*]:-none}"
echo "old data file: ${OLD_DATA_FILE:-none}"

# Refuse before changing anything.
if [ -n "$OLD_DATA_FILE" ] && { [ -e "$OLD_DATA_FILE" ] || [ -L "$OLD_DATA_FILE" ]; }; then
  [ ! -L "$OLD_DATA_FILE" ] || die "$OLD_DATA_FILE is a symlink; move the real file to $NEW_DATA_FILE by hand"
  [ ! "$OLD_DATA_FILE" -ef "$NEW_DATA_FILE" ] || die "$OLD_DATA_FILE and $NEW_DATA_FILE are the same file"
fi

# --- 3. prep: everything that can fail, before the running service is touched --------------
say "Preparing $ROOT"
mkdir -p "$NEW_DATA_DIR"

# The unit is generated on every run, so it always matches this folder and the node found now.
# It runs as root: this folder usually lives under /root, which no other user can enter.
cat > "$NEW_UNIT" <<EOF
[Unit]
Description=Kung room sync server
After=network-online.target
Wants=network-online.target

[Service]
WorkingDirectory=$ROOT
EnvironmentFile=$NEW_ENV
ExecStart=$NODE $NEW_SERVER
Restart=always
RestartSec=5
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=no
PrivateTmp=yes
ReadWritePaths=$NEW_DATA_DIR

[Install]
WantedBy=multi-user.target
EOF
chmod 644 "$NEW_SERVER" "$NEW_UNIT"
echo "unit file: generated $NEW_UNIT (node: $NODE)"

# env file (never printed)
ENV_CREATED=0
if [ ! -f "$NEW_ENV" ]; then
  if [ -n "$OLD_ENV" ]; then
    ( umask 077; cp -- "$OLD_ENV" "$NEW_ENV" )
    echo "env file: copied from $OLD_ENV"
  elif [ -f "$ROOT/.env.example" ]; then
    ( umask 077; cp -- "$ROOT/.env.example" "$NEW_ENV" )
    echo "env file: created from $ROOT/.env.example"
  else
    ( umask 077; printf 'KUNG_ROOM_DATA_PATH=%s\n' "$NEW_DATA_FILE" > "$NEW_ENV" )
    echo "WARNING: no old env file and no $ROOT/.env.example (a dotfile, easy to miss when copying)."
    echo "         Created a minimal $NEW_ENV with only KUNG_ROOM_DATA_PATH. Copy .env.example into"
    echo "         $ROOT and add the settings you need (KUNG_ROOM_WRITER_UUIDS, HYPIXEL_API_KEY ...)."
  fi
  ENV_CREATED=1
fi
env_set "$NEW_ENV" KUNG_ROOM_DATA_PATH "$NEW_DATA_FILE"

# An old host.conf drop-in overrides ExecStart with the old server path. Its only job was the bind
# address: keep that in the env file (moved aside below, once the old service is stopped).
HOST_FROM_DROPIN=""
dropin_src=""
if [ -f "$DROPIN" ]; then dropin_src=$DROPIN; elif [ -f "$DROPIN$ASIDE" ]; then dropin_src=$DROPIN$ASIDE; fi
if [ -n "$dropin_src" ]; then
  h=$(grep -o 'KUNG_ROOM_SYNC_HOST=[^[:space:]]*' "$dropin_src" | head -n 1 | cut -d= -f2 || true)
  if [ -n "$h" ]; then
    cur_host=$(env_get "$NEW_ENV" KUNG_ROOM_SYNC_HOST)
    if [ "$ENV_CREATED" -eq 1 ] || [ -z "$cur_host" ] || [ "$cur_host" = "$h" ]; then
      env_set "$NEW_ENV" KUNG_ROOM_SYNC_HOST "$h"
      HOST_FROM_DROPIN=$h
      if [ "$cur_host" != "$h" ]; then echo "bind address: $h (from the old host.conf drop-in) is now in the env file"; fi
    else
      echo "WARNING: the old drop-in bound the server to $h but $NEW_ENV sets KUNG_ROOM_SYNC_HOST=$cur_host; check it"
    fi
  fi
fi
chown root:root "$NEW_ENV"
chmod 600 "$NEW_ENV"

# Warn about settings of the old unit that nothing migrates (key names only).
all_text=$unit_text
if [ -f "$DROPIN" ]; then all_text=$all_text$'\n'$(cat -- "$DROPIN"); fi
all_text=$(printf '%s\n' "$all_text" | awk '!seen[$0]++')   # systemctl cat already includes drop-ins
lost_keys=$( {
    printf '%s\n' "$all_text" | sed -nE 's/^[[:space:]]*Environment=//p'
    printf '%s\n' "$all_text" | sed -nE 's/^[[:space:]]*ExecStart=[-@+!:]*[^[:space:]]*\/env[[:space:]]+//p'
  } | tr -s '[:space:]' '\n' | sed -nE "s/^[\"']?([A-Za-z_][A-Za-z0-9_]*)=.*/\1/p" | sort -u | tr '\n' ' ')
if [ -n "$HOST_FROM_DROPIN" ]; then lost_keys=${lost_keys//KUNG_ROOM_SYNC_HOST /}; fi
if [ -n "${lost_keys// /}" ]; then
  echo "WARNING: the old unit sets variables that are NOT migrated; put them in $NEW_ENV if needed: $lost_keys"
fi
n_envfiles=$(printf '%s\n' "$all_text" | grep -cE '^[[:space:]]*EnvironmentFile=' || true)
if [ "${n_envfiles:-0}" -gt 1 ]; then
  echo "WARNING: the old unit has $n_envfiles EnvironmentFile= lines; only ${OLD_ENV:-none} was migrated:"
  printf '%s\n' "$all_text" | sed -nE 's/^[[:space:]]*EnvironmentFile=-?/  /p'
fi

# data: copy next to the target, verify, then move into place. A truncated target never exists.
DATA_VERIFIED=0
if [ -n "$OLD_DATA_FILE" ] && [ -f "$OLD_DATA_FILE" ]; then
  if [ ! -e "$NEW_DATA_FILE" ]; then
    tmp=$NEW_DATA_FILE.tmp
    rm -f -- "$tmp"
    if ! { cp -p -- "$OLD_DATA_FILE" "$tmp" && cmp -s -- "$OLD_DATA_FILE" "$tmp" && mv -f -- "$tmp" "$NEW_DATA_FILE"; }; then
      rm -f -- "$tmp"
      die "copying $OLD_DATA_FILE to $NEW_DATA_FILE failed; nothing was changed or deleted"
    fi
  fi
  if cmp -s -- "$OLD_DATA_FILE" "$NEW_DATA_FILE"; then
    DATA_VERIFIED=1
    echo "data: $NEW_DATA_FILE is identical to $OLD_DATA_FILE"
  else
    echo "note: $NEW_DATA_FILE differs from $OLD_DATA_FILE. Both are kept; the old file is not offered for deletion."
  fi
fi

PORT=$(env_get "$NEW_ENV" KUNG_ROOM_SYNC_PORT)
case $PORT in ''|*[!0-9]*) PORT=8765 ;; esac

port_busy() {
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | awk -v p=":$PORT" 'length($4) >= length(p) && substr($4, length($4) - length(p) + 1) == p { f = 1 } END { exit !f }'
  else
    timeout 3 "$NODE" -e "const s=require('net').connect($PORT,'127.0.0.1');s.on('connect',()=>process.exit(0));s.on('error',()=>process.exit(1))"
  fi
}
probe() {
  timeout 3 "$NODE" -e "fetch('http://127.0.0.1:$PORT/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
}

# --- 4. swap (from here on a failure rolls back) -------------------------------------------
if [ "$(systemctl is-active "$UNIT" 2>/dev/null || true)" = active ]; then WAS_ACTIVE=1; fi
if [ "$(systemctl is-enabled "$UNIT" 2>/dev/null || true)" = enabled ]; then WAS_ENABLED=1; fi

say "Switching $UNIT to $NEW_UNIT"
ARMED=1
systemctl stop "$UNIT" 2>/dev/null || true
if [ -f "$DROPIN" ]; then
  mv -f -- "$DROPIN" "$DROPIN$ASIDE"
  DROPIN_MOVED=1
fi
if [ "$(readlink -f -- "$UNIT_LINK" 2>/dev/null || true)" != "$NEW_UNIT" ]; then
  if [ -L "$UNIT_LINK" ]; then
    OLD_LINK_TARGET=$(readlink -- "$UNIT_LINK")
    rm -f -- "$UNIT_LINK"
  elif [ -e "$UNIT_LINK" ]; then
    mv -f -- "$UNIT_LINK" "$UNIT_LINK$ASIDE"
    UNIT_MOVED=1
  fi
  systemctl link "$NEW_UNIT"
  LINKED=1
fi
systemctl daemon-reload

if port_busy; then
  die "port $PORT is still in use after stopping $UNIT (old pm2, docker or another unit?). Free it and run again"
fi

ENABLED_BY_US=1
systemctl enable --now "$UNIT"

# --- 5. health: every check must pass ------------------------------------------------------
say "Health check on 127.0.0.1:$PORT (up to 15 s)"
ok=0
for _ in $(seq 1 15); do
  if probe; then ok=1; break; fi
  sleep 1
done
if [ "$ok" -eq 1 ]; then
  sleep 3   # settle: a crash loop shows up as a restart
  probe || ok=0
  [ "$(systemctl is-active "$UNIT" 2>/dev/null || true)" = active ] || ok=0
  nrestarts=$(systemctl show -p NRestarts --value "$UNIT" 2>/dev/null || true)
  [ "${nrestarts:-0}" = 0 ] || ok=0
  exec_line=$(systemctl show -p ExecStart --value "$UNIT" 2>/dev/null || true)
  case $exec_line in *"$NEW_SERVER"*) ;; *) ok=0 ;; esac   # a leftover drop-in could start the old file
fi
if [ "$ok" -ne 1 ]; then
  echo "Health check FAILED (not healthy, not active, restarted, or the unit does not start $NEW_SERVER)." >&2
  journalctl -u "$UNIT" -n 30 --no-pager || true
  exit 1   # the EXIT trap rolls back
fi
ARMED=0
echo "healthy, active, no restarts, runs $NEW_SERVER"

# --- 6. cleanup of the old locations: exact files only, then empty directories -------------
DEL_FILES=(); DEL_DIRS=()
add_file() {
  local p=$1 x
  [ -n "$p" ] || return 0
  [ -f "$p" ] || [ -L "$p" ] || return 0
  case $p in "$ROOT"|"$ROOT"/*) return 0 ;; esac
  for x in "${DEL_FILES[@]:-}"; do [ "$x" != "$p" ] || return 0; done
  DEL_FILES+=("$p")
  return 0
}
add_dir() {
  local p=${1%/} x
  [ -n "$p" ] || return 0
  [ -d "$p" ] || [ -L "$p" ] || return 0
  case $p in "$ROOT"|"$ROOT"/*) return 0 ;; esac
  for x in "${DEL_DIRS[@]:-}"; do [ "$x" != "$p" ] || return 0; done
  DEL_DIRS+=("$p")
  return 0
}

for d in "${OLD_DIRS[@]:-}"; do
  if [ -n "$d" ]; then add_file "$d/kung-room-sync-server.mjs"; add_dir "$d"; fi
done
if [ -n "$OLD_ENV" ]; then
  if env_covered "$OLD_ENV" "$NEW_ENV"; then
    add_file "$OLD_ENV"
  else
    echo "kept: $OLD_ENV (not everything in it is in $NEW_ENV; compare the keys above)"
  fi
fi
add_file "$UNIT_LINK$ASIDE"
add_file "$DROPIN$ASIDE"
add_dir "$(dirname -- "$DROPIN")"
if [ "$DATA_VERIFIED" -eq 1 ]; then
  old_data_dir=$(dirname -- "$OLD_DATA_FILE")
  add_file "$OLD_DATA_FILE"
  real=$(readlink -f -- "$old_data_dir" 2>/dev/null || true)   # DynamicUser: a symlink into /var/lib/private
  if [ -n "$real" ] && [ "$real" != "$old_data_dir" ]; then add_dir "$real"; fi
  add_dir "$old_data_dir"
elif [ -n "$OLD_DATA_FILE" ] && [ -f "$OLD_DATA_FILE" ]; then
  echo "kept: $OLD_DATA_FILE (not verified identical to the new data file)"
fi

if [ "${#DEL_FILES[@]}" -eq 0 ] && [ "${#DEL_DIRS[@]}" -eq 0 ]; then
  say "No old files left to delete"
else
  say "Old files, no longer used:"
  if [ "${#DEL_FILES[@]}" -gt 0 ]; then printf '  file: %s\n' "${DEL_FILES[@]}"; fi
  if [ "${#DEL_DIRS[@]}" -gt 0 ]; then
    echo "and these directories, only if they are empty afterwards:"
    printf '  dir:  %s\n' "${DEL_DIRS[@]}"
  fi
  if [ "$ASSUME_YES" -eq 1 ]; then
    ans=y
  else
    printf 'Delete these? [y/N] '
    read -r ans || ans=""
  fi
  case $ans in
    y|Y|yes|YES)
      for p in "${DEL_FILES[@]:-}"; do
        if [ -n "$p" ] && rm_file "$p"; then echo "deleted: $p"; fi
      done
      for _ in 1 2; do   # deepest directories may need a second pass
        for p in "${DEL_DIRS[@]:-}"; do
          if [ -n "$p" ]; then rmdir_if_empty "$p"; fi
        done
      done
      for p in "${DEL_DIRS[@]:-}"; do
        if [ -z "$p" ]; then continue; fi
        if [ -e "$p" ] || [ -L "$p" ]; then echo "kept (not empty): $p"; else echo "removed: $p"; fi
      done
      ;;
    *) echo "nothing deleted" ;;
  esac
fi

# --- 7. docker leftovers: print only -------------------------------------------------------
if command -v docker >/dev/null 2>&1; then
  found=$(docker ps -a --format '{{.Names}} {{.Image}}' 2>/dev/null | grep -i kung || true)
  if [ -n "$found" ]; then
    say "Docker leftovers (not touched, run these yourself if you want them gone):"
    while read -r cname cimage; do
      echo "  docker rm -f $cname"
      echo "  docker rmi $cimage"
    done <<< "$found"
  fi
fi

# --- 8. result -----------------------------------------------------------------------------
say "Result"
ls -la "$ROOT" "$NEW_DATA_DIR"
echo "service: $(systemctl is-active "$UNIT" || true)"
echo "Set HYPIXEL_API_KEY and KUNG_ROOM_WRITER_UUIDS in $NEW_ENV, then: systemctl restart $UNIT"
line=$(journalctl -u "$UNIT" -n 100 --no-pager 2>/dev/null | grep 'any verified player' | tail -n 1 || true)
if [ -n "$line" ]; then echo "$line"; fi
