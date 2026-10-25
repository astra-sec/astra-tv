# APK 签名与校验

## 验证发行版

Astra TV 的发行版使用 Astra Sec 证书签名。公开证书为 [astra-sec-release.pem](astra-sec-release.pem)，指纹与有效期信息见 [astra-sec-release.json](astra-sec-release.json)。这些文件不包含私钥或密码，不能用于签署 APK。

发行证书 SHA-256：

```text
4a07274dddebcd69a6107b0e3bd7f0da1499bbc74d00b9c02abb1cdc88e8daf1
```

使用 Android SDK Build Tools 中的 `apksigner` 检查 APK：

```sh
apksigner verify --verbose --print-certs /path/to/astra-tv.apk
```

确认验证成功，并将输出中的证书 SHA-256 与上述指纹比较。

## 从源码构建

调试版本不需要发行版密钥：

```sh
./gradlew :app:assembleDebug
```

需要生成未签名 release 时：

```sh
./gradlew :app:assembleRelease -PastraUnsigned=true
```

未签名 APK 需要签名后才能安装。若要用自己的密钥直接构建 release，在仓库外创建私密的 properties 文件，并填写以下配置项：

| 配置项 | 内容 |
| --- | --- |
| `ASTRA_SIGNING_STORE_FILE` | 自己的 keystore 路径；相对路径以配置文件所在目录为基准 |
| `ASTRA_SIGNING_STORE_PASSWORD` | keystore 密码 |
| `ASTRA_SIGNING_KEY_ALIAS` | 签名密钥别名 |
| `ASTRA_SIGNING_KEY_PASSWORD` | 签名密钥密码 |

通过参数指定配置文件：

```sh
./gradlew :app:assembleRelease -PastraSigningProperties=/path/to/signing.properties
```

也可通过同名环境变量提供这四个值。设置 `ASTRA_SIGNING_STORE_FILE` 环境变量时，构建会优先使用环境变量中的完整配置。不要将 keystore、密码或含密码的配置文件提交到仓库。

## 安装与更新

相同包名的应用通常需要相同签名才能直接覆盖更新。自行签名或 debug 构建的 APK 无法直接覆盖 Astra Sec 签名的发行版；后续自行构建时应持续使用自己的同一套签名。
