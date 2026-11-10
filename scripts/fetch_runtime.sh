#!/usr/bin/env bash
# Builds app/src/main/assets/{runtime.zip,htdocs.zip}:
#   runtime.zip -> usr/bin/php (static, from static-php.dev)
#                  usr/bin/mariadbd + usr/lib/*.so + usr/share (from Termux)
#   htdocs.zip  -> OpenCart "upload" folder, ready for the web installer
set -euo pipefail

PHP_SERIES="${PHP_SERIES:-8.3}"
OPENCART_VERSION="${OPENCART_VERSION:-4.0.2.3}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/runtime-work"
OUT="$WORK/out"
ASSETS="$ROOT/app/src/main/assets"
rm -rf "$OUT" && mkdir -p "$OUT/usr/bin" "$ASSETS" "$WORK"

# ---------- PHP ----------
php_file=""
for flavor in bulk common; do
  listing="$(curl -fsSL "https://dl.static-php.dev/static-php-cli/$flavor/" || true)"
  php_file="$(grep -oE "php-${PHP_SERIES//./\\.}\.[0-9]+-cli-linux-aarch64\.tar\.gz" <<<"$listing" | sort -uV | tail -1 || true)"
  if [ -n "$php_file" ]; then break; fi
done
[ -n "$php_file" ] || { echo "No static PHP $PHP_SERIES build found"; exit 1; }
echo "PHP: $flavor/$php_file"
curl -fsSL -o "$WORK/php.tgz" "https://dl.static-php.dev/static-php-cli/$flavor/$php_file"
mkdir -p "$WORK/php" && tar -xzf "$WORK/php.tgz" -C "$WORK/php"
php_bin="$(find "$WORK/php" -type f -name php | head -1)"
cp "$php_bin" "$OUT/usr/bin/php"
chmod +x "$OUT/usr/bin/php"
file "$OUT/usr/bin/php" || true
if command -v qemu-aarch64-static >/dev/null; then
  echo "PHP modules:"; qemu-aarch64-static "$OUT/usr/bin/php" -m || true
fi

# ---------- MariaDB ----------
python3 "$ROOT/scripts/termux_mariadb.py" "$OUT/usr" "$WORK/termux"

echo "php=$php_file" > "$OUT/usr/share/runtime-info.txt"
echo "mariadb=$(cat "$OUT/usr/share/mariadb-version.txt")" >> "$OUT/usr/share/runtime-info.txt"
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
rm -f "$ASSETS/htdocs.zip"
(cd "$upload" && zip -qr -9 "$ASSETS/htdocs.zip" .)

ls -lh "$ASSETS"
