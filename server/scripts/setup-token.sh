#!/bin/sh
set -eu

config_dir="${XDG_CONFIG_HOME:-$HOME/.config}/vocaphone"
token_file="$config_dir/token"
legacy_token="${XDG_CONFIG_HOME:-$HOME/.config}/localflow/token"

mkdir -p "$config_dir"
chmod 700 "$config_dir"
if [ ! -f "$token_file" ]; then
  if [ -f "$legacy_token" ]; then
    cp "$legacy_token" "$token_file"
    printf 'Migrated Local Flow token from %s\n' "$legacy_token"
  else
    umask 077
    openssl rand -base64 48 > "$token_file"
  fi
fi
chmod 600 "$token_file"
printf 'Token is stored at %s\n' "$token_file"
