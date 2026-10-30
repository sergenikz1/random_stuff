#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd -- "$script_dir/.." && pwd)
signing_store=${SCRYERTV_KEYSTORE_FILE:?Set SCRYERTV_KEYSTORE_FILE to the permanent release key}
password_file=${SCRYERTV_PASSWORD_FILE:?Set SCRYERTV_PASSWORD_FILE to a protected password file}
sdk_dir=${ANDROID_HOME:?Set ANDROID_HOME}
tools_dir="$sdk_dir/build-tools/35.0.0"
unsigned_apk="$project_dir/app/build/outputs/apk/release/app-release-unsigned.apk"
release_dir="$project_dir/out"
mkdir -p "$release_dir"
version=$(python3 -c 'import pathlib,re,sys; print(re.search(r"versionName\s*=\s*\"([^\"]+)\"", pathlib.Path(sys.argv[1]).read_text())[1])' "$project_dir/app/build.gradle.kts")
apk="$release_dir/ScryerTV-$version.apk"
"$tools_dir/zipalign" -c 4 "$unsigned_apk"
"$tools_dir/apksigner" sign --ks "$signing_store" --ks-key-alias scryertv \
    --ks-pass "file:$password_file" --out "$apk" "$unsigned_apk"
python3 "$script_dir/create-update-manifest.py" --apk "$apk" --build-tools "$tools_dir" --output "$release_dir/scryer-tv-update.json"
(cd "$release_dir" && sha256sum "ScryerTV-$version.apk" scryer-tv-update.json > SHA256SUMS.txt)
