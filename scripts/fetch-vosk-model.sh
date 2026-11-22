#!/usr/bin/env bash
# Downloads the German Vosk model, verifies its checksum and unpacks it into the app assets.
# The model (~95 MB unpacked) is bundled into the APK but kept out of git.
#
# Usage: scripts/fetch-vosk-model.sh [path/to/vosk-model-small-de-0.15.zip]
#   With a path, the given zip is used instead of downloading it again.
set -euo pipefail

NAME="vosk-model-small-de-0.15"
URL="https://alphacephei.com/vosk/models/${NAME}.zip"
SHA256="b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${ROOT}/app/src/main/assets/model-de"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [[ $# -ge 1 ]]; then
  ZIP="$1"
else
  ZIP="${WORK}/${NAME}.zip"
  echo "Downloading ${URL}"
  curl -fSL --progress-bar -o "$ZIP" "$URL"
fi

echo "Verifying checksum"
ACTUAL="$(shasum -a 256 "$ZIP" | awk '{print $1}')"
if [[ "$ACTUAL" != "$SHA256" ]]; then
  echo "Checksum mismatch: expected ${SHA256}, got ${ACTUAL}" >&2
  exit 1
fi

echo "Unpacking into ${DEST}"
unzip -q "$ZIP" -d "$WORK/unpacked"
rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
mv "$WORK/unpacked/${NAME}" "$DEST"
# Vosk's StorageService re-unpacks the assets only when this id changes.
echo "${NAME}" > "${DEST}/uuid"

echo "Done: $(du -sh "$DEST" | awk '{print $1}') in ${DEST}"
