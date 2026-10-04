# FFmpeg audio module

Java and JNI bridge files come from AndroidX Media3 tag **1.6.1**,
`libraries/decoder_ffmpeg`, under the Apache License 2.0. The upstream bridge
is unchanged. `LICENSE-MEDIA3` contains its license.

FFmpeg source is **6.0.1**, provided by the `third_party/ffmpeg` submodule
from the official mirror https://github.com/FFmpeg/FFmpeg.git, tag `n6.0.1`,
fixed commit `c41ff724ede7da657762d61097e26fac296c53bf`.
Initialize it with `git submodule update --init --recursive`.

The prebuilt libraries were built from the matching official release archive
https://ffmpeg.org/releases/ffmpeg-6.0.1.tar.xz. Its SHA-256 is:
`9b16b8731d78e596b4be0d720428ca42df642bb2d78342881ff7f5bc29fc9623`.
FFmpeg is built with GPL and nonfree features disabled and only the `mp3`,
`aac`, `ac3`, and `eac3` audio decoders, avcodec, avutil, and swresample enabled.
It is provided under LGPL 2.1 or later for this configuration. Corresponding
source and upstream license files are in the pinned `third_party/ffmpeg`.
The source packaging script expands this submodule and supplies the release
`VERSION` file, so the resulting ZIP contains full corresponding source.
GitHub's automatic repository ZIP does not include submodule contents.

Rebuild the native libraries with `scripts/build-ffmpeg.sh`. Set
`ANDROID_NDK_ROOT` if NDK 27.0.12077973 is installed elsewhere. API level 21 is
used for both ARM ABIs. The script uses the pinned source and preserves
configuration manifests. It creates static archives and headers under
`.build/ffmpeg/<abi>/install`, then links the unchanged JNI bridge into
`src/main/jniLibs/<abi>/libffmpegJNI.so`. The full app source and these build
instructions permit rebuilding with a modified LGPL library. The script passes
`revision=6.0.1` to make to retain the original release version string rather
than the Git tag's leading `n`.

This module does not encode media and does not provide an FFmpeg video
decoder. Video remains on Android's platform MediaCodec decoders. MP2 audio
is handled by FFmpeg's `mp3` decoder, following the official Media3 mapping:
https://developer.android.com/media/media3/exoplayer/supported-formats.

The upstream archive SHA-256 for Media3 1.6.1 is
`122111698575dd2abdf8d9fe9b8b2b85ee6ec35918c75f1fee26b5f7030127af`.
Upstream module instructions are preserved in `UPSTREAM-README.md`.
