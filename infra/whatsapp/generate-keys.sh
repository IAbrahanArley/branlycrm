#!/usr/bin/env bash
# Generates the next version of the WhatsApp channel key pair (ADR-036):
#   <dir>/public/channel-key-v<N>.pub.pem   mounted in the API and the worker (seal only)
#   <dir>/private/channel-key-v<N>.pem      mounted only in the worker (opens tokens)
# Older private keys stay in place so tokens sealed with them can still be opened and resealed.
# Back up the private directory outside the VM, separately from the database backups.
# Usage: bash infra/whatsapp/generate-keys.sh [directory]   (default: ./.whatsapp-keys)
set -euo pipefail

dir="${1:-./.whatsapp-keys}"
mkdir -p "$dir/public" "$dir/private"
chmod 700 "$dir/private" 2>/dev/null || true

last=0
for file in "$dir"/public/channel-key-v*.pub.pem; do
  [[ -e "$file" ]] || continue
  n="${file##*channel-key-v}"
  n="${n%.pub.pem}"
  [[ "$n" =~ ^[0-9]+$ ]] && (( n > last )) && last="$n"
done
version=$((last + 1))

private="$dir/private/channel-key-v${version}.pem"
public="$dir/public/channel-key-v${version}.pub.pem"
umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$private" 2>/dev/null
openssl pkey -in "$private" -pubout -out "$public"
chmod 600 "$private" 2>/dev/null || true
chmod 644 "$public" 2>/dev/null || true

echo "Created key version $version in $dir (private key: back it up outside the VM)."
