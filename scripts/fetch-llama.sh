#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
commit=19ff817fb25e9a1b838b1d7f9f4325f7bf9779c4
if [ -f vendor/llama.cpp/.source-revision ] && [ "$(cat vendor/llama.cpp/.source-revision)" = "$commit" ]; then exit 0; fi
mkdir -p vendor
curl --fail --location --retry 3 "https://github.com/ggml-org/llama.cpp/archive/$commit.tar.gz" -o vendor/llama.tar.gz
mkdir -p vendor/llama.cpp
tar -xzf vendor/llama.tar.gz --strip-components=1 -C vendor/llama.cpp
printf '%s' "$commit" > vendor/llama.cpp/.source-revision
rm vendor/llama.tar.gz
