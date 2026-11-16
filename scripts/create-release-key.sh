#!/usr/bin/env bash
# Creates the release signing key once and hands it to the release pipeline.
# - Keystore (PKCS12, RSA 4096, valid 100 years) at $1 or ~/.config/masal/release.jks, outside the repo.
# - A random password, written to .env (gitignored) and never printed.
# - GitHub repository secrets MASAL_KEYSTORE_BASE64, MASAL_KEYSTORE_PASSWORD, MASAL_KEY_ALIAS,
#   MASAL_KEY_PASSWORD for the current repository (needs the gh CLI, logged in).
# Back up the keystore and its password: without them no update of a released app can be signed.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077

KEYSTORE="${1:-$HOME/.config/masal/release.jks}"
ALIAS=masal
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
if [ -e "$KEYSTORE" ]; then echo "$KEYSTORE already exists; not overwriting a signing key." >&2; exit 1; fi
command -v gh >/dev/null || { echo "The gh CLI is needed to set the repository secrets." >&2; exit 1; }

mkdir -p "$(dirname "$KEYSTORE")"
PASSWORD=$(openssl rand -base64 32 | tr -d '/+=' | cut -c1-32)
"$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" -keyalg RSA -keysize 4096 \
  -validity 36500 -storepass "$PASSWORD" -keypass "$PASSWORD" -dname "CN=Masal" >/dev/null 2>&1
chmod 600 "$KEYSTORE"

touch .env
{
  echo
  echo "# Release signing (keystore outside the repo; back up both)"
  echo "MASAL_KEYSTORE_FILE=$KEYSTORE"
  echo "MASAL_KEYSTORE_PASSWORD=$PASSWORD"
  echo "MASAL_KEY_ALIAS=$ALIAS"
  echo "MASAL_KEY_PASSWORD=$PASSWORD"
} >> .env

base64 -w0 "$KEYSTORE" | gh secret set MASAL_KEYSTORE_BASE64
printf '%s' "$PASSWORD" | gh secret set MASAL_KEYSTORE_PASSWORD
printf '%s' "$PASSWORD" | gh secret set MASAL_KEY_PASSWORD
printf '%s' "$ALIAS" | gh secret set MASAL_KEY_ALIAS
unset PASSWORD

echo "Created $KEYSTORE, saved its password in .env and set the release secrets on GitHub."
echo "Back up the keystore and the password from .env somewhere safe."
