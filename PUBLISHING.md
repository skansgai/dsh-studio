# 发布到 JetBrains 插件市场（Marketplace）

把 DeepSeek Harness Studio 发布到 [plugins.jetbrains.com](https://plugins.jetbrains.com)，让所有 Android Studio / IntelliJ IDEA 用户都能搜索、安装和自动更新。

> 本文基于 JetBrains 官方文档整理（2025 版流程）：
> - [Publishing a Plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)
> - [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)

## 总览

```
① 注册 JetBrains 账号 → ② 生成签名证书 → ③ 配置密钥 → ④ 构建+签名 → ⑤ 手动上传（首次）→ ⑥ 等审核
```

- **首次发布必须手动上传**到市场网页；之后的新版本才可以用 Gradle 自动发布。
- **发布前插件必须签名**（作者签名 + 市场二次签名）；未签名插件在 IDE 安装时会弹警告。

---

## ① 注册 JetBrains 账号

1. 打开 [JetBrains Account Center](https://account.jetbrains.com)，点击 **Create Account**（可直接用 Google/GitHub 账号登录）。
2. 用该账号登录 [JetBrains Marketplace](https://plugins.jetbrains.com/author/me)。

## ② 生成签名证书（只需做一次）

需要 `openssl`（本机已装：`D:\soft\miniconda3\Library\bin\openssl.exe`，OpenSSL 3.0.16；或在 Git Bash 里 `openssl` 也可用）。在项目外的安全目录执行：

```bash
mkdir C:\dsh-signing && cd C:\dsh-signing

# 1) 生成加密的 RSA 私钥（会提示设置私钥密码，请记住）
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096

# 2) 转换成签名工具需要的 RSA 形式
openssl rsa -in private_encrypted.pem -out private.pem

# 3) 生成证书链（自签名证书，有效期 365 天，过期后需重新生成）
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

产出三个文件：`private.pem`（私钥，**绝对不要提交/外传**）、`chain.crt`（证书链）、`private_encrypted.pem`（加密备份）。私钥密码即第 1 步设置的密码。

> 证书有效期 365 天，到期前需要重新生成并发布新版本；可把命令里的 `-days 365` 改成 `-days 3650` 用更久。

## ③ 把证书信息交给构建脚本（三选一）

本项目已配好 `signPlugin`/`publishPlugin` 任务，敏感值通过以下任一种方式注入（**不要写进 build.gradle.kts / gradle.properties / git**）：

**方式 A：命令行参数**
```bash
gradlew.bat signPlugin ^
  -PsignPlugin.certificateChainFile=C:\dsh-signing\chain.crt ^
  -PsignPlugin.privateKeyFile=C:\dsh-signing\private.pem ^
  -PsignPlugin.password=你的私钥密码
```

**方式 B：环境变量**（Windows PowerShell）
```powershell
$env:ORG_GRADLE_PROJECT_signPlugin.certificateChainFile = "C:\dsh-signing\chain.crt"
$env:ORG_GRADLE_PROJECT_signPlugin.privateKeyFile       = "C:\dsh-signing\private.pem"
$env:ORG_GRADLE_PROJECT_signPlugin.password             = "你的私钥密码"
```

**方式 C：本地文件 `signing.local.properties`**（已 gitignore，最方便）
在项目根目录创建 `signing.local.properties`：
```properties
signPlugin.certificateChainFile=C\:/dsh-signing/chain.crt
signPlugin.privateKeyFile=C\:/dsh-signing/private.pem
signPlugin.password=你的私钥密码
```

## ④ 构建并签名

```bash
gradlew.bat buildPlugin
gradlew.bat signPlugin   # 需先按 ③ 提供证书
```

签名后的分发包是 `build/distributions/dsh-studio-0.1.0-signed.zip`（`signPlugin` 会另外生成 `-signed.zip`，不会改动未签名版本）。**上传市场时请用这个 `-signed.zip`**。

> 你可以用官方工具验证签名：在 Gradle 缓存中找到 `marketplace-zip-signer-cli-0.1.43.jar`，执行
> `java -jar marketplace-zip-signer-cli-0.1.43.jar verify -cert chain.crt -in dsh-studio-0.1.0-signed.zip`，
> 退出码 0 表示签名有效。

> 也可以一步完成：`gradlew.bat buildPlugin signPlugin`。

## ⑤ 手动上传（首次发布必须）

1. 登录 [plugins.jetbrains.com/author/me](https://plugins.jetbrains.com/author/me)，点 **Add new plugin**。
2. 填写表单：
   - **Plugin name**：`DeepSeek Harness Studio`（市场内必须唯一，若被占用需改名）
   - **Description**：默认读取 plugin.xml 的描述，可再补充（支持 Markdown/HTML）
   - **Category**：选择 `Tools Integration` 或 `Other`
   - **License**：选择 **MIT**（本项目使用 MIT）
   - **Tags**：`deepseek`、`harness`、`android studio`、`ai` 等
   - **Compatibility**：自动读取（since-build 231 = Android Studio 2023.1+ / IDEA 2023.1+）
   - **Screenshots**：建议上传 1–3 张工具窗口截图（本机可先截图：AS 里打开工具窗口后 Win+Shift+S）
3. 上传 `build/distributions/dsh-studio-0.1.0-signed.zip`，点 **Add the plugin**。

## ⑥ 等待审核

上传后 JetBrains 会自动运行 **Plugin Verifier** 做兼容性/安全性检查，通常几分钟到几小时。期间插件为“未验证”状态；通过后即可被搜索到。

本地可先自查（可选，会下载多个 IDE 发行版、体积大）：
```bash
gradlew.bat verifyPlugin          # 描述符校验（本项目已通过）
gradlew.bat runPluginVerifier     # 深度兼容性验证（需在 build.gradle.kts 配置 verifier 版本）
```

## ⑦ 后续版本：用 Gradle 自动发布

1. 在 [My Tokens](https://plugins.jetbrains.com/author/me/tokens) 生成 **Personal Access Token**（只显示一次，立即复制保存）。
2. 提供 token（三选一，同 ③）：
   ```bash
   gradlew.bat publishPlugin -PintellijPlatformPublishingToken=perm:xxxxx
   ```
   或环境变量 `ORG_GRADLE_PROJECT_intellijPlatformPublishingToken=perm:xxxxx`，
   或写入 `signing.local.properties`：`intellijPlatformPublishingToken=perm:xxxxx`。
3. `publishPlugin` 会自动先执行 `signPlugin`（前提是按 ③ 提供了证书）。

---

## 注意事项

- **版本号必须递增**：市场拒绝相同版本号的重复上传。改版本号：修改 `build.gradle.kts` 的 `version = "0.1.0"`。
- **私钥安全**：`private.pem` 和 token 不要提交到 git、不要发到群里；泄露=他人可冒充你发布。证书文件用 `.gitignore` 里的 `signing.local/` 目录存放。
- **plugin.xml 的 vendor 信息**：作者名已设为 `Yang SongSong`，邮箱已填 `yangsongsong66@gmail.com`。如需更换，直接改 `src/main/resources/META-INF/plugin.xml` 后重新 `gradlew buildPlugin signPlugin` 即可。
- **until-build**：本项目不设置 until-build，即兼容所有未来版本（市场推荐做法）；如需限制可在 plugin.xml 加 `<idea-version until-build="253.*"/>`。
- **市场规则**：描述/截图里不能有广告、误导信息；不得捆绑未经声明的二进制；遵循 [Marketplace Guidelines](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)。
- **网络限制**：本机访问 `plugins.jetbrains.com` 的部分接口返回 403，上传表单可能加载较慢/需科学上网；Gradle 自动发布走 `https://plugins.jetbrains.com/plugin/uploadPlugin`，若被墙同样需要代理。

## 常见问题

| 问题 | 处理 |
|---|---|
| 上传提示“Plugin is not signed” | 按 ②③④ 生成证书并执行 `signPlugin` 后重新上传 |
| 验证失败：since-build 警告 | 非阻断；构建目标（242）高于声明的 since-build（231）属正常 |
| 市场里搜不到 | 插件默认状态为未验证/私有；审核通过后公开；也可先发到 beta/alpha channel 测试 |
| 名字被占用 | 换名（如 “DeepSeek Harness” / “DSH Studio”），同时改 plugin.xml 的 `<name>` |
| 想只给自己/团队用 | 发布时选择私有，或用自定义插件仓库分发 |

## 参考

- [Publishing a Plugin（官方）](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)
- [Plugin Signing（官方）](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
- [Marketplace 上传新插件](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)
- [Marketplace 列表最佳实践](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)
