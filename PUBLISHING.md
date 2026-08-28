# Publishing to the JetBrains Marketplace

English | [中文](PUBLISHING.zh.md)

Publish DeepSeek Harness Studio to [plugins.jetbrains.com](https://plugins.jetbrains.com) so every Android Studio / IntelliJ IDEA user can search, install and update it.

> Based on the official JetBrains docs (2025 flow):
> - [Publishing a Plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)
> - [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)

## Overview

```
1. Register a JetBrains account → 2. Generate a signing certificate → 3. Provide the key to the build → 4. Build + sign → 5. Upload manually (first release) → 6. Wait for review
```

- **The first release must be uploaded manually** on the marketplace website; later versions can be published with Gradle.
- **Plugins must be signed before publishing** (author signature + marketplace signature); unsigned plugins show a warning dialog on install.

---

## 1. Register a JetBrains account

1. Open the [JetBrains Account Center](https://account.jetbrains.com) and click **Create Account** (you can sign in with Google/GitHub).
2. Sign in to the [JetBrains Marketplace](https://plugins.jetbrains.com/author/me) with that account.

## 2. Generate a signing certificate (one-time)

You need `openssl`. Run these in a safe directory **outside the project**:

```bash
mkdir C:\dsh-signing && cd C:\dsh-signing

# 1) Generate an encrypted RSA private key (you will set a passphrase — remember it)
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096

# 2) Convert to the RSA form the signer needs
openssl rsa -in private_encrypted.pem -out private.pem

# 3) Generate the certificate chain (self-signed cert, valid 365 days; regenerate when it expires)
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

Three files are produced: `private.pem` (private key — **never commit or share it**), `chain.crt` (certificate chain), `private_encrypted.pem` (encrypted backup). The private-key passphrase is the one you set in step 1.

> The certificate is valid for 365 days; regenerate and release a new version before it expires. You can use `-days 3650` for a longer validity.

## 3. Provide the certificate to the build (pick one)

The project already wires up `signPlugin` / `publishPlugin`. Provide the secrets through any of these (never hard-code them in `build.gradle.kts` / `gradle.properties` / git):

**Option A: command-line arguments**
```bash
gradlew.bat signPlugin ^
  -PsignPlugin.certificateChainFile=C:\dsh-signing\chain.crt ^
  -PsignPlugin.privateKeyFile=C:\dsh-signing\private.pem ^
  -PsignPlugin.password=YOUR_PRIVATE_KEY_PASSPHRASE
```

**Option B: environment variables** (Windows PowerShell)
```powershell
$env:ORG_GRADLE_PROJECT_signPlugin.certificateChainFile = "C:\dsh-signing\chain.crt"
$env:ORG_GRADLE_PROJECT_signPlugin.privateKeyFile       = "C:\dsh-signing\private.pem"
$env:ORG_GRADLE_PROJECT_signPlugin.password             = "YOUR_PRIVATE_KEY_PASSPHRASE"
```

**Option C: a local `signing.local.properties` file** (gitignored, easiest)
Create `signing.local.properties` in the project root:
```properties
signPlugin.certificateChainFile=C\:/dsh-signing/chain.crt
signPlugin.privateKeyFile=C\:/dsh-signing/private.pem
signPlugin.password=YOUR_PRIVATE_KEY_PASSPHRASE
```

## 4. Build and sign

```bash
gradlew.bat buildPlugin
gradlew.bat signPlugin   # requires the certificate from step 3
```

The signed distribution is `build/distributions/dsh-studio-0.1.0-signed.zip` (`signPlugin` produces a separate `-signed.zip` and leaves the unsigned one untouched). **Upload this `-signed.zip` to the marketplace.**

> You can verify the signature with the official tool: find `marketplace-zip-signer-cli-0.1.43.jar` in the Gradle cache and run
> `java -jar marketplace-zip-signer-cli-0.1.43.jar verify -cert chain.crt -in dsh-studio-0.1.0-signed.zip`.
> Exit code 0 means the signature is valid.

> Or do it in one step: `gradlew.bat buildPlugin signPlugin`.

## 5. Upload manually (required for the first release)

1. Sign in to [plugins.jetbrains.com/author/me](https://plugins.jetbrains.com/author/me) and click **Add new plugin**.
2. Fill in the form:
   - **Plugin name**: `DeepSeek Harness Studio` (must be unique on the marketplace; rename if taken)
   - **Description**: pre-filled from plugin.xml; you can extend it (Markdown/HTML supported)
   - **Category**: `Tools Integration` or `Other`
   - **License**: **MIT** (this project uses MIT)
   - **Tags**: `deepseek`, `harness`, `android studio`, `ai`, etc.
   - **Compatibility**: read automatically (since-build 231 = Android Studio 2023.1+ / IDEA 2023.1+)
   - **Screenshots**: 1–3 tool-window screenshots recommended (in AS, open the tool window and press Win+Shift+S)
3. Upload `build/distributions/dsh-studio-0.1.0-signed.zip` and click **Add the plugin**.

## 6. Wait for review

After uploading, JetBrains automatically runs the **Plugin Verifier** for compatibility/security checks — usually minutes to a few hours. The plugin is "unverified" during this time; once it passes it becomes searchable.

Optional local pre-checks (downloads several IDE distributions, large):
```bash
gradlew.bat verifyPlugin          # descriptor check (passes for this project)
gradlew.bat runPluginVerifier     # deep compatibility check (configure verifier versions in build.gradle.kts)
```

## 7. Later versions: publish with Gradle

1. Generate a **Personal Access Token** at [My Tokens](https://plugins.jetbrains.com/author/me/tokens) (shown only once — copy and save it immediately).
2. Provide the token (any of the three ways, same as step 3):
   ```bash
   gradlew.bat publishPlugin -PintellijPlatformPublishingToken=perm:xxxxx
   ```
   or the env var `ORG_GRADLE_PROJECT_intellijPlatformPublishingToken=perm:xxxxx`,
   or add `intellijPlatformPublishingToken=perm:xxxxx` to `signing.local.properties`.
3. `publishPlugin` runs `signPlugin` automatically first (as long as the certificate from step 3 is configured).

---

## Notes

- **Versions must increase**: the marketplace rejects re-uploading the same version. Bump it in `build.gradle.kts` (`version = "0.1.0"`).
- **Private key safety**: never commit `private.pem` or the token to git or share them; a leak lets someone impersonate you. Keep cert files in the gitignored `signing.local/` directory.
- **plugin.xml vendor info**: the author is set to `Yang SongSong`, email `yangsongsong66@gmail.com`. To change, edit `src/main/resources/META-INF/plugin.xml` and re-run `gradlew buildPlugin signPlugin`.
- **until-build**: this project intentionally has no until-build, i.e. compatible with all future versions (marketplace-recommended). To restrict, add `<idea-version until-build="253.*"/>` to plugin.xml.
- **Marketplace rules**: no ads or misleading info in the description/screenshots; no undeclared bundled binaries; follow the [Marketplace Guidelines](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html).
- **Network**: some `plugins.jetbrains.com` endpoints return 403 from this machine — the upload form may be slow or need a proxy; Gradle publishing goes through `https://plugins.jetbrains.com/plugin/uploadPlugin` and needs a proxy if blocked.

## FAQ

| Issue | Fix |
|---|---|
| "Plugin is not signed" on upload | Follow steps 2–4, generate the certificate, run `signPlugin`, re-upload |
| Verification fails with since-build warning | Non-blocking; build target (242) being higher than declared since-build (231) is expected |
| Not searchable on the marketplace | Plugin is unverified/private by default; it becomes public after review; you can also test via beta/alpha channels |
| Name taken | Rename (e.g. "DeepSeek Harness" / "DSH Studio") and update `<name>` in plugin.xml |
| Want it for yourself/team only | Publish as private, or distribute via a custom plugin repository |

## References

- [Publishing a Plugin (official)](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)
- [Plugin Signing (official)](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
- [Uploading a new plugin](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)
- [Best practices for listing](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)
