#!/bin/sh
# Creates FolioTone's own release signing key, independent of Android Studio, in a private folder
# outside the repository (default: ~/Projects/Signings/foliotone-signing), like diacritica-signing:
#
#   foliotone-release.jks            the key (PKCS12, RSA 4096, valid ~27 years)
#   FOLIOTONE_KEYSTORE_BASE64.txt    the key as one base64 line (GitHub secret KEYSTORE_BASE64)
#   keystore.properties              path, alias and password for local Gradle builds
#   README-signing.txt               secrets, certificate fingerprint and how to restore them
#
# It also copies keystore.properties into the project (ignored by git), so Android Studio builds are
# signed with the same key as GitHub releases, and, if the GitHub CLI is installed and signed in,
# replaces the repository's four signing secrets. Pass --no-github to skip that step.
#
# Usage: tools/create-release-key.sh [--no-github] [destination folder]
set -eu

REPO="antonio-jf/foliotone"
ALIAS="foliotone"
GITHUB=1
if [ "${1:-}" = "--no-github" ]; then GITHUB=0; shift; fi
DEST="${1:-$HOME/Projects/Signings/foliotone-signing}"
PROJECT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
STORE="$DEST/foliotone-release.jks"

if [ -e "$STORE" ]; then
  echo "A key already exists at $STORE; not replacing it (that would break updates)." >&2
  exit 1
fi

# macOS's /usr/bin/keytool is only a stub without a JDK, so prefer Android Studio's bundled Java.
KEYTOOL=""
for candidate in "${JAVA_HOME:-/nonexistent}/bin/keytool" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
    "$(command -v keytool 2>/dev/null || true)"; do
  if [ -x "$candidate" ] && "$candidate" -help >/dev/null 2>&1; then KEYTOOL="$candidate"; break; fi
done
[ -n "$KEYTOOL" ] || { echo "keytool not found: install Android Studio or a JDK 17." >&2; exit 1; }

mkdir -p "$DEST"
chmod 700 "$DEST"
umask 077
PASSWORD="$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 28)"

"$KEYTOOL" -genkeypair -keystore "$STORE" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "CN=FolioTone, O=Antonio de Jesus Juan Fernandez" >/dev/null 2>&1

base64 < "$STORE" | tr -d '\n' > "$DEST/FOLIOTONE_KEYSTORE_BASE64.txt"
SHA256="$("$KEYTOOL" -list -v -keystore "$STORE" -storepass "$PASSWORD" -alias "$ALIAS" \
  | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -1)"

cat > "$DEST/keystore.properties" <<PROPS
storeFile=$STORE
storePassword=$PASSWORD
keyAlias=$ALIAS
keyPassword=$PASSWORD
PROPS

cat > "$DEST/README-signing.txt" <<NOTE
FolioTone release signing key. KEEP THIS FOLDER PRIVATE AND BACKED UP (e.g. in a password manager or on an encrypted drive).
If the key is lost, new releases can no longer update the installed app (you'd have to uninstall; back up your data first).

Repository secrets on GitHub:
https://github.com/$REPO/settings/secrets/actions  ->  "New repository secret"

  Name: KEYSTORE_BASE64     Value: the whole contents of FOLIOTONE_KEYSTORE_BASE64.txt (one long line)
  Name: KEYSTORE_PASSWORD   Value: $PASSWORD
  Name: KEY_ALIAS           Value: $ALIAS
  Name: KEY_PASSWORD        Value: $PASSWORD

Or, from this folder with the GitHub CLI:
  gh secret set KEYSTORE_BASE64 -R $REPO < FOLIOTONE_KEYSTORE_BASE64.txt
  gh secret set KEYSTORE_PASSWORD -R $REPO -b '$PASSWORD'
  gh secret set KEY_ALIAS -R $REPO -b $ALIAS
  gh secret set KEY_PASSWORD -R $REPO -b '$PASSWORD'

Local builds: copy keystore.properties into the FolioTone project folder (it is ignored by git).
Android Studio then signs debug and release builds with this key, so they install over GitHub releases.

Certificate SHA-256: $SHA256
NOTE

cp "$DEST/keystore.properties" "$PROJECT/keystore.properties"
echo "Created $DEST"
echo "Certificate SHA-256: $SHA256"
echo "Copied keystore.properties into $PROJECT (ignored by git)."

if [ "$GITHUB" = 1 ]; then
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    gh secret set KEYSTORE_BASE64 -R "$REPO" < "$DEST/FOLIOTONE_KEYSTORE_BASE64.txt"
    printf '%s' "$PASSWORD" | gh secret set KEYSTORE_PASSWORD -R "$REPO"
    printf '%s' "$ALIAS" | gh secret set KEY_ALIAS -R "$REPO"
    printf '%s' "$PASSWORD" | gh secret set KEY_PASSWORD -R "$REPO"
    echo "Updated the four signing secrets on $REPO."
  else
    echo "GitHub CLI not available or not signed in: set the secrets by hand (see README-signing.txt)."
  fi
fi
