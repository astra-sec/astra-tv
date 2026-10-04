# Astra TV

Astra TV 是用 Kotlin 编写的 Android / Android TV IPTV 客户端，包名和命名空间均为 `org.astrasec.tv`。应用内名称为 Astra TV，桌面入口显示“电视直播”。最低 Android 5.0 / API 21。

![Astra TV 界面](docs/screenshots/overview.png)

截图包含测试频道与测试源，电视播放画面来自较早版本。

## 安装与首次设置

安装 `astra-tv-0.4.1.apk`。首次打开填写 HTTP/HTTPS 的 M3U 直播源地址；直播源没有预设值，安装包不内置频道列表。节目单使用一个可配置的 XMLTV 地址，支持 XML 或 XML.gz，默认值是：

`http://epg.51zmt.top:8000/e.xml.gz`

节目单可以留空关闭。确认配置前不下载频道或节目单，也不启动播放。无效地址会保留输入并提示修正；遥控器数字和左右键用于输入，上下键依次切换字段和保存按钮。

两个地址保存在应用私有 SharedPreferences，频道缓存按直播源 URL 区分，节目单缓存按节目单地址和频道身份区分。保存后加载来源中的频道并播放；后续启动恢复该来源缓存及上次频道，后台刷新。没有广告、账号、购买功能或人为启动等待。

## 遥控器与界面

- 全屏上键增加频道、下键减少频道，按来源列表顺序循环；频道加减键同样可换台。
- 确定或左键打开菜单，列表内上下移动、确定播放。菜单键与左键使用同一操作逻辑。
- 左侧导航仅有“频道”和“设置”，焦点移到对应项时，右侧直接显示频道列表或设置选项。
- 右键进入当前页面，左键从内容返回导航；内容页内右键或返回键回到全屏。重新打开菜单默认选择当前频道。
- 全屏连续按两次返回退出。数字键输入来源里的频道号后选台。
- 设置包括视频解码器、画面比例、刷新频道列表、直播源地址、节目单地址和开源许可。已有地址可以编辑；读取失败仍可通过设置修正。

支持触屏点击视频打开菜单。最后频道、地址、画面比例及解码器选择持久保存。进后台时释放播放器，回到前台重新连接。频道底栏在播放恢复后按 4.5 秒计时隐藏。

## 节目单

频道列表第二行与换台底栏显示当前节目和北京时间播出时段，节目边界自动更新。仅使用设置的单一来源，不聚合或自动切换其他服务。匹配使用 `tvg-id`、`tvg-name` 和保守的频道别名；没有匹配或排期过期时显示“暂无节目单”。4K、国际版本及不同服务会明确区分。

节目单在后台流式解析，每 6 小时检查更新，跨日或排期耗尽时提前刷新。失败保留有效缓存并隔 10 分钟重试；重新保存地址可立即重试。更新不改变频道焦点、滚动位置或底栏显示状态。实际覆盖取决于所选直播源和节目单内容。

## 播放与兼容性

使用 AndroidX Media3 1.6.1 播放 HTTP/HTTPS MPEG-TS 直播，视频经 Android MediaCodec 优先使用设备硬件解码器，并可在设置中选择固件提供的解码器。初始化失败或部分运行错误会尝试兼容的备用解码器。HLS、DASH、RTSP 和直接 UDP 来源尚未实现专用播放路径。

音频优先使用系统解码；设备不支持的 MP2、AAC、AC3 或 EAC3 可回退到 FFmpeg 音频扩展。FFmpeg 6.0.1 只启用 `mp3`（包含 MP1/MP2）、AAC、AC3、EAC3，禁用 GPL、nonfree、视频编解码、网络及多余组件。HEVC、4K、10-bit 等视频能否播放取决于设备硬件。网络重连使用有上限的指数退避。

## 构建与测试

需要 JDK 17 或 21、Android SDK platform 35 及对应 build-tools。用 Android Studio 打开项目，或运行：

```sh
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

通过本地 `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME` 指定 SDK。项目包含 ARM64、ARMv7 的 FFmpeg JNI 音频库，普通 APK 构建无需重编原生库。`third_party/ffmpeg` 是官方 FFmpeg 仓库的子模块，固定为 6.0.1（`n6.0.1`）。首次获取项目：

```sh
git clone --recurse-submodules https://github.com/astra-sec/astra-tv.git
```

已有项目可初始化子模块，然后重新构建音频库：

```sh
git submodule update --init --recursive
ANDROID_NDK_ROOT=/path/to/android-ndk ./scripts/build-ffmpeg.sh
```

脚本包含完整 configure 与链接参数，使用 NDK r27、API 21 和 16 KiB ELF 对齐。生成的配置快照属于本机构建产物，不进入版本控制；对应源码及重建步骤完整保留。Media3 Java/JNI 桥代码和许可在 `ffmpeg-audio`。

测试使用 `example.test` 的通用 M3U 样例，覆盖地址解析、元数据、重复频道、节目单格式、匹配、版本隔离和失效边界。设备验证报告保存在本地 `diagnostics/`，不纳入 Git 或源码包。

当前 release 使用本机 Android 开发签名，供本地侧载。签名私钥不在仓库中；不同构建机器需要使用兼容签名才能覆盖同一应用。
