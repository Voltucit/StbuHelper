#!/bin/bash
set -euo pipefail

fail() { printf '%s\n' "$*" >&2; exit 1; }

configuration=${1:-}
case "$configuration" in
  Debug|Release) ;;
  *) fail "用法：/bin/bash ios/Scripts/package.sh Debug|Release" ;;
esac

[[ "$(uname -s)" == Darwin ]] || fail "iOS 打包需要在装有 Xcode 的 Mac 上运行。"
ios_dir="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$ios_dir"

# IDEA started from Finder might not inherit Homebrew's PATH.
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
command -v xcodegen >/dev/null || fail "请安装 XcodeGen，例如 brew install xcodegen。"

sdk=iphoneos
destination='generic/platform=iOS'
output="$ios_dir/$configuration"
method=app-store-connect
if [[ "$configuration" == Debug ]]; then
  if [[ "${STBU_IOS_DEBUG_DEVICE:-0}" == 1 ]]; then
    output="$output/Device"
    method=debugging
  else
    sdk=iphonesimulator
    destination='generic/platform=iOS Simulator'
  fi
fi
/usr/bin/xcrun --sdk "$sdk" --show-sdk-path >/dev/null ||
  fail "找不到 $sdk。请在 Xcode 完成首次设置并安装所需的 iOS 组件。"

build_dir="$ios_dir/build"
mkdir -p "$build_dir" "$output"
stage="$(mktemp -d "$build_dir/package.XXXXXX")"
trap 'rm -rf -- "$stage"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Keep the previous output until its replacement has been built.
publish() {
  local artifact="$1" target="$2" previous="$stage/previous-$(basename "$2")"
  if [[ -e "$target" || -L "$target" ]]; then mv "$target" "$previous"; fi
  if ! mv "$artifact" "$target"; then
    if [[ -e "$previous" || -L "$previous" ]] && ! mv "$previous" "$target"; then
      trap - EXIT
      printf '原产物仍保留在：%s\n' "$previous" >&2
    fi
    return 1
  fi
  printf '输出：%s\n' "$target"
}

xcodegen generate --use-cache
derived="$build_dir/$configuration-$sdk"
build_args=(-project "$ios_dir/StbuHelper.xcodeproj" -scheme StbuHelper
  -configuration "$configuration" -sdk "$sdk" -destination "$destination" -derivedDataPath "$derived")

if [[ "$sdk" == iphonesimulator ]]; then
  /usr/bin/xcodebuild "${build_args[@]}" CODE_SIGNING_ALLOWED=NO build
  app="$derived/Build/Products/Debug-iphonesimulator/StbuHelper.app"
  [[ -d "$app" ]] || fail "构建结束，但找不到 StbuHelper.app。"
  /usr/bin/ditto "$app" "$stage/StbuHelper.app"
  publish "$stage/StbuHelper.app" "$output/StbuHelper.app"
  exit 0
fi

options="${STBU_IOS_EXPORT_OPTIONS:-}"
if [[ -n "$options" ]]; then
  [[ -f "$options" ]] || fail "找不到导出配置：$options"
  /usr/bin/plutil -lint "$options" >/dev/null
  export_destination="$(/usr/libexec/PlistBuddy -c 'Print :destination' "$options" 2>/dev/null || true)"
  [[ -z "$export_destination" || "$export_destination" == export ]] ||
    fail "IDEA 打包只允许本地导出，请将 ExportOptions 的 destination 设为 export。"
else
  options="$stage/ExportOptions.plist"
  cat > "$options" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>$method</string>
  <key>destination</key><string>export</string>
</dict>
</plist>
EOF
fi

archive="$stage/StbuHelper.xcarchive"
export_args=(-exportArchive -archivePath "$archive" -exportPath "$stage/export" -exportOptionsPlist "$options")
if [[ "${STBU_IOS_ALLOW_PROVISIONING_UPDATES:-0}" == 1 ]]; then
  build_args+=(-allowProvisioningUpdates)
  export_args+=(-allowProvisioningUpdates)
fi
if [[ -n "${STBU_IOS_TEAM:-}" ]]; then
  build_args+=("DEVELOPMENT_TEAM=$STBU_IOS_TEAM" CODE_SIGN_STYLE=Automatic)
fi

/usr/bin/xcodebuild "${build_args[@]}" -archivePath "$archive" archive
/usr/bin/xcodebuild "${export_args[@]}"
packages=("$stage/export/"*.ipa)
[[ "${#packages[@]}" == 1 && -f "${packages[0]}" ]] ||
  fail "应导出一个 IPA，请检查 Xcode 导出方式或关闭按设备拆分。"
publish "$archive" "$output/StbuHelper.xcarchive"
publish "${packages[0]}" "$output/StbuHelper.ipa"
