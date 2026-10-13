#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
android_dir=$(cd -- "${script_dir}/.." && pwd)
signing_store=${LIMITPULSE_KEYSTORE:?Set LIMITPULSE_KEYSTORE to the original release keystore path}
password_file=${LIMITPULSE_PASSWORD_FILE:?Set LIMITPULSE_PASSWORD_FILE to a protected password file}
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "${sdk_dir}" ]; then echo 'Set ANDROID_HOME to the Android SDK path' >&2; exit 1; fi
build_tools="${sdk_dir}/build-tools/35.0.0"
unsigned_apk="${android_dir}/app/build/outputs/apk/release/app-release-unsigned.apk"
release_dir="${android_dir}/app/build/outputs/release"
mkdir -p "${release_dir}"
apk_version=$("${build_tools}/aapt" dump badging "${unsigned_apk}" | sed -n "s/.*versionName='\([0-9][0-9.]*\)'.*/\1/p")
if [[ ! "${apk_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo 'Unsupported APK version' >&2; exit 1
fi
apk_file="limit-pulse-v${apk_version}.apk"
apk="${release_dir}/${apk_file}"
"${build_tools}/zipalign" -c -v 4 "${unsigned_apk}" >/dev/null
"${build_tools}/apksigner" sign --ks "${signing_store}" --ks-key-alias limitpulse \
    --ks-pass "file:${password_file}" \
    --out "${apk}" "${unsigned_apk}"
"${build_tools}/apksigner" verify --verbose --print-certs "${apk}"
keytool -exportcert -rfc -alias limitpulse -keystore "${signing_store}" \
    -storepass:file "${password_file}" -file "${release_dir}/signing-certificate.pem"
cd "${release_dir}"
sha256sum "${apk_file}" signing-certificate.pem > SHA256SUMS
echo "Verified signed APK: ${apk}"
