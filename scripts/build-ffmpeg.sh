#!/usr/bin/env bash
# Rebuild the FFmpeg audio-only JNI library from the pinned source submodule.
# Requires an Android NDK and standard make; no video encoder/decoder is enabled.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE_DIR="$PROJECT_DIR/third_party/ffmpeg"
NDK_DIR="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/27.0.12077973}"
API_LEVEL=21
BUILD_JOBS="${BUILD_JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)}"
case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64 ;;
    Linux) HOST_TAG=linux-x86_64 ;;
    *) echo "Build on macOS or Linux with the Android NDK." >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin"
if [[ ! -f "$SOURCE_DIR/configure" ]]; then
    echo "Missing FFmpeg source. Run git submodule update --init --recursive." >&2
    exit 1
fi
if [[ ! -x "$TOOLCHAIN/llvm-ar" ]]; then
    echo "Missing Android NDK. Set ANDROID_NDK_ROOT." >&2
    exit 1
fi
IFS= read -r FFMPEG_RELEASE_VERSION < "$SOURCE_DIR/RELEASE"

build_abi() {
    local abi="$1" arch="$2" target="$3"
    local build_dir="$PROJECT_DIR/.build/ffmpeg/$abi"
    local install_dir="$build_dir/install"
    local output_dir="$PROJECT_DIR/ffmpeg-audio/src/main/jniLibs/$abi"
    mkdir -p "$build_dir" "$output_dir"
    cd "$build_dir"
    "$SOURCE_DIR/configure" \
        --prefix="$install_dir" \
        --target-os=android --arch="$arch" --enable-cross-compile \
        --cc="$TOOLCHAIN/${target}${API_LEVEL}-clang" \
        --cxx="$TOOLCHAIN/${target}${API_LEVEL}-clang++" \
        --ar="$TOOLCHAIN/llvm-ar" --nm="$TOOLCHAIN/llvm-nm" \
        --ranlib="$TOOLCHAIN/llvm-ranlib" --strip="$TOOLCHAIN/llvm-strip" \
        --enable-static --disable-shared --enable-pic \
        --disable-doc --disable-programs --disable-everything \
        --disable-avdevice --disable-avformat --disable-swscale \
        --disable-postproc --disable-avfilter --disable-symver \
        --disable-v4l2-m2m --disable-vulkan --disable-network \
        --disable-autodetect --disable-gpl --disable-nonfree \
        --enable-swresample --enable-decoder=mp3,aac,ac3,eac3 \
        --extra-cflags="-O2 -fPIC -ffunction-sections -fdata-sections" \
        --extra-ldflags="-Wl,-z,max-page-size=16384"
    # Keep the release version string; Git tags have an extra leading 'n'.
    make -j"$BUILD_JOBS" "revision=$FFMPEG_RELEASE_VERSION"
    make install "revision=$FFMPEG_RELEASE_VERSION"
    "$TOOLCHAIN/${target}${API_LEVEL}-clang++" \
        -std=c++11 -O2 -fPIC -shared -static-libstdc++ \
        -I"$install_dir/include" \
        "$PROJECT_DIR/ffmpeg-audio/src/main/jni/ffmpeg_jni.cc" \
        "$install_dir/lib/libswresample.a" \
        "$install_dir/lib/libavcodec.a" \
        "$install_dir/lib/libavutil.a" \
        -landroid -llog -lm -pthread \
        -Wl,-Bsymbolic -Wl,--gc-sections -Wl,--exclude-libs,ALL \
        -Wl,-z,max-page-size=16384 \
        -o "$output_dir/libffmpegJNI.so"
    "$TOOLCHAIN/llvm-strip" --strip-unneeded "$output_dir/libffmpegJNI.so"
    cp config.h config_components.h ffbuild/config.mak "$output_dir/"
    # Configuration manifests are useful when auditing/reproducing native builds,
    # but only the .so is packaged from jniLibs by the Android Gradle plugin.
    echo "Built $abi: $output_dir/libffmpegJNI.so"
}

build_abi arm64-v8a aarch64 aarch64-linux-android
build_abi armeabi-v7a arm armv7a-linux-androideabi
