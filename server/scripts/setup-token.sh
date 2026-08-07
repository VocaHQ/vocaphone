#!/bin/sh
set -eu

config_dir="${XDG_CONFIG_HOME:-$HOME/.config}/vocaphone"
token_file="$config_dir/token"
legacy_token="${XDG_CONFIG_HOME:-$HOME/.config}/localflow/token"
legacy_data="${XDG_DATA_HOME:-$HOME/.local/share}/localflow"

mkdir -p "$config_dir"
chmod 700 "$config_dir"

# Treat a missing or empty vocaphone token as unset so an empty placeholder
# cannot block migration the way a real secret would.
if [ -s "$token_file" ]; then
  :
elif [ -n "${LOCALFLOW_TOKEN:-}" ]; then
  printf '%s\n' "$LOCALFLOW_TOKEN" > "$token_file"
  printf 'Migrated Local Flow token from LOCALFLOW_TOKEN\n'
elif [ -s "$legacy_token" ]; then
  cp "$legacy_token" "$token_file"
  printf 'Migrated Local Flow token from %s\n' "$legacy_token"
elif [ -d "$legacy_data" ] && [ -n "$(ls -A "$legacy_data" 2>/dev/null || true)" ]; then
  printf 'Found Local Flow data at %s but no bootstrap token.\n' "$legacy_data" >&2
  printf 'Copy %s to %s (or set VOCAPHONE_TOKEN) before minting a new secret so paired phones are not locked out.\n' \
    "$legacy_token" "$token_file" >&2
  exit 1
else
  umask 077
  openssl rand -base64 48 > "$token_file"
fi

chmod 600 "$token_file"
printf 'Token is stored at %s\n' "$token_file"
