#!/usr/bin/env bash
# Baut die signierten APKs einer Version, schreibt update.json und gibt die Befehle
# zum Veröffentlichen aus. Ausgeführt werden sie nur mit --publish.
#
#   tools/release.sh "Kurzer Änderungstext"            # bauen, Datei schreiben, Befehle anzeigen
#   tools/release.sh "Kurzer Änderungstext" --publish  # zusätzlich GitHub-Release anlegen
#
# Die Version steht in app/build.gradle.kts (appVersion, appBuild). Das Skript liest
# sie von dort. Schlüssel bleiben lokal in signing/ und werden nirgends kopiert.
set -euo pipefail

CHANGES="${1:-}"
PUBLISH="${2:-}"
if [ -z "$CHANGES" ]; then
    echo "Aufruf: tools/release.sh \"Änderungstext\" [--publish]" >&2
    exit 2
fi
if [ -n "$PUBLISH" ] && [ "$PUBLISH" != "--publish" ]; then
    echo "Unbekannter Schalter: $PUBLISH" >&2
    exit 2
fi

cd "$(dirname "$0")/.."
REPO="theubyte/Android-Native-Lab"
GRADLE="${GRADLE:-gradle}"
ABIS="arm64-v8a armeabi-v7a x86_64"

if [ ! -f signing/keystore.properties ]; then
    echo "signing/keystore.properties fehlt. Ohne den festen Schlüssel lassen sich die APKs nicht über die installierte App spielen." >&2
    exit 1
fi

read_version() {
    python3 - <<'PY'
import re
s = open('app/build.gradle.kts', encoding='utf8').read()
v = [int(x) for x in re.search(r'appVersion = listOf\((\d+), (\d+), (\d+)\)', s).groups()]
b = int(re.search(r'appBuild = (\d+)', s).group(1))
print('.'.join(map(str, v)), v[0] * 1000000 + v[1] * 10000 + v[2] * 100 + b)
PY
}
read -r VERSION CODE < <(read_version)
TAG="schulranzen-v$VERSION"
OUT="app/build/outputs/apk/release"

echo "Version $VERSION (Code $CODE), Tag $TAG"
"$GRADLE" --no-daemon --max-workers=4 :core:test :app:assembleRelease

# Die Dateien bekommen Version und ABI im Namen, damit sie am Release eindeutig sind.
DIST="app/build/dist/$VERSION"
rm -rf "$DIST"
mkdir -p "$DIST"
for abi in $ABIS; do
    src="$OUT/app-$abi-release.apk"
    if [ ! -f "$src" ]; then
        echo "Nicht gefunden: $src" >&2
        exit 1
    fi
    cp "$src" "$DIST/schulranzen-$VERSION-$abi.apk"
done

CHANGES="$CHANGES" VERSION="$VERSION" CODE="$CODE" TAG="$TAG" REPO="$REPO" DIST="$DIST" ABIS="$ABIS" python3 - <<'PY'
import hashlib, json, os
apks = {}
for abi in os.environ['ABIS'].split():
    name = f"schulranzen-{os.environ['VERSION']}-{abi}.apk"
    path = os.path.join(os.environ['DIST'], name)
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    apks[abi] = {
        'url': f"https://github.com/{os.environ['REPO']}/releases/download/{os.environ['TAG']}/{name}",
        'sha256': h.hexdigest(),
        'size': os.path.getsize(path),
    }
manifest = {
    'versionCode': int(os.environ['CODE']),
    'versionName': os.environ['VERSION'],
    'changes': os.environ['CHANGES'],
    'apks': apks,
}
with open('update.json', 'w', encoding='utf8', newline='\n') as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
    f.write('\n')
for abi, a in apks.items():
    print(f"{abi:12} {a['size'] / 1e6:6.1f} MB  {a['sha256']}")
PY

# Zuerst das Release, erst dann update.json pushen: Sonst bekommen Geräte eine URL, die es noch nicht gibt.
CREATE=(gh release create "$TAG" --repo "$REPO" --title "Schulranzen $VERSION" --notes "$CHANGES" "$DIST"/*.apk)

echo
echo "update.json ist geschrieben. So geht es weiter:"
echo "  1. $(printf '%q ' "${CREATE[@]}")"
echo "  2. git add update.json && git commit -m \"Schulranzen $VERSION: update.json\" && git push"
echo

if [ "$PUBLISH" = "--publish" ]; then
    echo "--publish: Release wird angelegt."
    "${CREATE[@]}"
    echo "Release angelegt. update.json musst du noch committen und pushen (Schritt 2)."
else
    echo "Nichts veröffentlicht. Zum Anlegen: Skript mit --publish aufrufen oder Befehl 1 ausführen."
fi
