#!/usr/bin/env bash
# Builds app/src/main/assets/{runtime.zip,htdocs.zip}:
#   runtime.zip -> usr/bin/php, usr/bin/mariadbd, usr/lib/*.so, usr/share
#                  (Termux packages, see termux_mariadb.py)
#   htdocs.zip  -> OpenCart "upload" folder, ready for the web installer
set -euo pipefail

OPENCART_VERSION="${OPENCART_VERSION:-4.0.2.3}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/runtime-work"
OUT="$WORK/out"
ASSETS="$ROOT/app/src/main/assets"
rm -rf "$OUT" && mkdir -p "$OUT/usr/bin" "$ASSETS" "$WORK"

# ---------- PHP + MariaDB (Termux packages) ----------
python3 "$ROOT/scripts/termux_mariadb.py" "$OUT/usr" "$WORK/termux"
echo "opencart=$OPENCART_VERSION" >> "$OUT/usr/share/runtime-info.txt"

rm -f "$ASSETS/runtime.zip"
(cd "$OUT" && zip -qr -9 "$ASSETS/runtime.zip" usr)

# ---------- OpenCart ----------
curl -fsSL -o "$WORK/opencart.zip" \
  "https://github.com/opencart/opencart/releases/download/${OPENCART_VERSION}/opencart-${OPENCART_VERSION}.zip"
rm -rf "$WORK/oc" && mkdir -p "$WORK/oc"
unzip -q "$WORK/opencart.zip" -d "$WORK/oc"
upload="$(find "$WORK/oc" -maxdepth 3 -type d -name upload | head -1)"
[ -n "$upload" ] || { echo "OpenCart upload/ folder not found"; exit 1; }
[ -f "$upload/config-dist.php" ] && mv "$upload/config-dist.php" "$upload/config.php"
[ -f "$upload/admin/config-dist.php" ] && mv "$upload/admin/config-dist.php" "$upload/admin/config.php"
# OpenCart 4.0.x predates PHP 8.4+: keep deprecation notices out of pages.
{ grep -rl --include='*.php' 'error_reporting(E_ALL)' "$upload" || true; } | while read -r f; do
  sed -i 's/error_reporting(E_ALL)/error_reporting(E_ALL \& ~E_DEPRECATED \& ~E_USER_DEPRECATED)/g' "$f"
  echo "patched: ${f#$upload/}"
done
grep -rn -A3 'set_error_handler' --include='*.php' "$upload/system" | head -20 || true
rm -f "$ASSETS/htdocs.zip"
(cd "$upload" && zip -qr -9 "$ASSETS/htdocs.zip" .)

ls -lh "$ASSETS"
