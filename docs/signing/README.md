# Astra Sec 正式签名

Astra TV 的正式 release 使用 Astra Sec 的长期签名。证书名称和组织均为 `Astra Sec`，不包含城市、省份或国家信息；RSA 4096 位，证书签名算法为 SHA256withRSA，有效期为 2026-10-12 至 2126-10-12。

此目录中的 PEM 证书和 JSON 信息均可公开。它们不包含私钥或密码，不能用来签署 APK。证书 SHA256：

```text
4a07274dddebcd69a6107b0e3bd7f0da1499bbc74d00b9c02abb1cdc88e8daf1
```

## 本地构建

私钥和随机密码保存在源码仓库之外，默认目录为 `~/.android/astra-sec/`：

| 文件 | 内容 |
| --- | --- |
| `astra-sec-release.p12` | 加密的 PKCS12 keystore，含私钥 |
| `signing.properties` | keystore 路径、别名及随机密码；这是私密文件 |
| `astra-sec-release.pem` | 可公开的证书 |
| `certificate-info.json` | 可公开的指纹和有效期信息 |

目录权限为 `700`，私密文件权限为 `600`。当前 keystore 和私钥使用同一个随机密码；密码保存在 `signing.properties` 中，无需写入命令、聊天或源码。

```sh
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

release 默认读取上述配置，没有配置时会明确报错，不会退回开发签名。debug 构建仍使用 Android 开发签名。若需其他私密配置位置，使用 `-PastraSigningProperties=/absolute/path/signing.properties`。只检查源码、不使用发行密钥时，可显式加上 `-PastraUnsigned=true` 生成未签名 release。

配置文件使用四个键：`ASTRA_SIGNING_STORE_FILE`、`ASTRA_SIGNING_STORE_PASSWORD`、`ASTRA_SIGNING_KEY_ALIAS`、`ASTRA_SIGNING_KEY_PASSWORD`。若设置了 `ASTRA_SIGNING_STORE_FILE` 环境变量，四个值将全部从环境变量读取，覆盖本地文件；这也是 CI 使用的方式。为避免密码进入配置缓存，项目默认关闭 Gradle configuration cache。

## GitHub Actions

仓库的四个 Actions Secrets 保存 keystore 的 Base64 内容、keystore 密码、alias 和私钥密码。Base64 只是编码，保密依赖 GitHub Secrets。可以使用以下命令从私密配置重新设置 Secrets；脚本经标准输入传递值，不输出密码或密钥：

```sh
python3 scripts/configure-signing-secrets.py --repo astra-sec/astra-tv
```

脚本需要已登录的 `gh` 和 JDK `keytool`；可用 `JAVA_HOME` 指定 JDK。设置前会核对 keystore 与公开证书的指纹。

`main` 的 push 和手动构建生成正式签名 APK，并验证签名和上述证书指纹，Artifacts 中的 APK 可直接安装。缺少 Secrets 时构建会失败。Pull Request 构建不使用密钥，产物明确标为 `-unsigned.apk`。密钥只在 runner 临时目录还原，任务结束删除；只上传 APK、校验文件、公开构建信息和检查报告。

只有需要发行的仓库才应配置这套 Secrets。工作流及 Gradle 脚本能使用签名材料，拥有主分支修改权限的维护者也属于密钥信任范围。

## 备份与恢复

1. 将 keystore 和密码备份到独立的受保护位置，例如加密离线介质和密码管理器。GitHub Secrets 不提供原值下载，不应作为唯一备份。
2. 换电脑时恢复 keystore 和 `signing.properties` 到上述目录，修正 `ASTRA_SIGNING_STORE_FILE` 路径，并恢复目录 `700`、文件 `600` 权限。核对公开证书指纹后再构建。
3. 同一 App 的后续版本持续使用这套签名。不要因换电脑或忘记密码重新生成密钥；重新生成会改变应用签名身份。
4. keystore、密码、含密码的配置或备份均不要提交 Git。`.gitignore` 是防误提交措施，实际材料应始终保存在仓库之外。

其他 Astra App 可以使用同一套签名，但需要分别配置其 release 构建和仓库 Secrets；本次只配置 Astra TV。

## 从旧版本迁移

切换正式签名前生成的本地 APK（旧的 0.4.2 及更早版本）使用 Android Debug 签名。这套新签名不能普通覆盖这些旧安装；迁移时先记录直播源、节目单地址及其他设置，再卸载旧包、安装正式包并重新输入配置。首次迁移完成后，本地和 Actions 生成的正式版本使用同一证书，可以继续覆盖升级。
