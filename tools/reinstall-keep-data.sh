#!/bin/sh
# Reinstall FolioTone when the installed copy was signed with a different key
# (INSTALL_FAILED_UPDATE_INCOMPATIBLE), keeping its books, progress, ratings and settings.
#
# Works only if the installed copy is a debug build (adb run-as needs that).
# The ElevenLabs API key cannot move: it is encrypted with a key Android deletes on uninstall,
# so re-enter it afterwards. Saved ElevenLabs audio does move and works again once the same key is entered.
#
# Usage, from the project folder with the phone connected and USB debugging on:
#   sh tools/reinstall-keep-data.sh
set -eu

PKG=com.linguareader
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"
STAMP=$(date +%Y%m%d-%H%M%S)
BACKUP="backups/phone-data-$STAMP.tar"

[ -x "$ADB" ] || { echo "adb not found at $ADB (set ADB=/path/to/adb)"; exit 1; }
[ -f "$APK" ] || { echo "APK not found at $APK. Run ./gradlew assembleDebug first."; exit 1; }
[ "$("$ADB" get-state 2>/dev/null)" = "device" ] || { echo "No phone connected (check USB debugging)."; exit 1; }
mkdir -p backups

echo "1/5 Checking that the installed app allows a data copy…"
if ! "$ADB" shell run-as "$PKG" true 2>/dev/null; then
  echo "The installed FolioTone is not a debug build, so its data cannot be copied."
  echo "Nothing was changed. Stop here and ask before uninstalling."
  exit 1
fi

echo "2/5 Stopping the app and copying its data to $BACKUP…"
"$ADB" shell am force-stop "$PKG"
"$ADB" exec-out run-as "$PKG" sh -c 'cd /data/data/'"$PKG"' && tar cf - $(ls -d files shared_prefs no_backup 2>/dev/null)' > "$BACKUP"
COUNT=$(tar tf "$BACKUP" | wc -l | tr -d ' ')
tar tf "$BACKUP" | grep -q 'files/library.json' || { echo "Backup has no library.json ($COUNT entries). Nothing was uninstalled."; exit 1; }
echo "    $COUNT entries, $(du -h "$BACKUP" | cut -f1) — library.json present."

echo "3/5 Uninstalling the old copy…"
"$ADB" uninstall "$PKG"

echo "4/5 Installing the new build…"
"$ADB" install "$APK"

echo "5/5 Restoring the data…"
"$ADB" shell am force-stop "$PKG"
"$ADB" exec-in run-as "$PKG" sh -c 'cd /data/data/'"$PKG"' && tar xf -' < "$BACKUP"
"$ADB" shell run-as "$PKG" ls files/library.json >/dev/null && echo "    Restored."

echo
echo "Done. Open FolioTone: your books, positions, ratings and settings should be back."
echo "Re-enter the ElevenLabs key if you use it. The backup stays in $BACKUP."
