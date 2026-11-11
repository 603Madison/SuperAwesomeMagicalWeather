#!/bin/sh
# Lets a .deb built on Ubuntu 24.04 install on 22.04 too. jpackage lists the
# build machine's library packages as dependencies, and 24.04 renamed some
# (libasound2 -> libasound2t64 and so on, the 64-bit time_t transition).
# Each renamed one becomes "new | old", which both releases can satisfy.
# Also: adding the menu entry can't fail the install.
set -eu
deb="$1"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fakeroot dpkg-deb -R "$deb" "$work/pkg"
control="$work/pkg/DEBIAN/control"
deps=$(sed -n 's/^Depends: //p' "$control")
fixed=$(printf '%s' "$deps" | tr ',' '\n' | sed 's/^ *//; s/ *$//' | sed -E '
  s/^(lib[a-z0-9.+-]+)t64$/\1t64 | \1/
  s/^libjpeg-turbo8$/libjpeg-turbo8 | libjpeg62-turbo/
' | paste -sd ',' - | sed 's/,/, /g')
sed -i "s/^Depends: .*/Depends: $fixed/" "$control"
# notify-send for notifications, secret-tool to keep keys in the login keyring.
grep -q '^Recommends:' "$control" || sed -i '/^Depends:/a Recommends: libnotify-bin, libsecret-tools' "$control"
# A system without a menu folder (a server, a container) can still install it.
sed -i '/|| true$/!s|^\(xdg-desktop-menu [a-z]* .*\)$|\1 \|\| true|' "$work/pkg/DEBIAN/postinst" "$work/pkg/DEBIAN/prerm"
fakeroot dpkg-deb -Zxz -b "$work/pkg" "$deb" > /dev/null
dpkg-deb -f "$deb" Depends
