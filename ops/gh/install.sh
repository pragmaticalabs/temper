#!/usr/bin/env bash
# install.sh -- install the GitHub App token wrappers for the current user:
#   ~/.local/bin/with-kiln-app, with-temper-app                 mode 0700
#   ~/.local/libexec/temper-gh/gh-app-token (off PATH)          mode 0700, in a 0700 directory
# Run it on bigboy as aether, from a checkout of ops/gh. It installs every with-* file of this directory (there is no
# with-reviewer: the reviewer PAT has no shell path) and gh-app-token, and touches nothing else.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$HOME/.local/bin" "$HOME/.local/libexec/temper-gh"
chmod 700 "$HOME/.local/libexec/temper-gh"
for wrapper in "$here"/with-*; do
  cp "$wrapper" "$HOME/.local/bin/$(basename "$wrapper")"
  chmod 700 "$HOME/.local/bin/$(basename "$wrapper")"
done
cp "$here/gh-app-token" "$HOME/.local/libexec/temper-gh/gh-app-token"
chmod 700 "$HOME/.local/libexec/temper-gh/gh-app-token"
echo "installed the with-* wrappers in $HOME/.local/bin; gh-app-token in $HOME/.local/libexec/temper-gh"
