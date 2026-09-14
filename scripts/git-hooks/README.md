# Git 钩子

## `pre-commit` —— 拦截「密文提交」

### 它解决什么问题

本机装的企业 DLP（E-SafeNet）会**透明加密**源码文件：

- 按扩展名生效 —— `.js` / `.ts` / `.json` 等会被加密，文件头变成 `E-SafeNet`；
  `.mjs` / `.mts` / `.cjs` / `.yaml` / `.zip` / `.jar` / `.txt` / `.md` 不受影响。
- 对**已经被加密过**的文件，只要它变回明文，1~2 秒内就会被重新加密。
- 解密按进程白名单：`python.exe` 读到明文，`git.exe` / `node.exe` / `java.exe` 读到原始密文。

最坑的地方是 **`git status` 完全看不出来**：工作区是明文、`git add` 之后暂存区可能已经是密文，
于是密文被当成正常源码提交进仓库，编译/运行时报一堆莫名其妙的错。

本项目真实踩过：`src/main/resources/dsh/overlay.js` 从提交 `93459ca5`（2026-09-07）起
在仓库里一直是 11546 字节的密文，导致插件注入网页的是乱码、「背景图 / 透明度」功能实际是坏的，
直到 2026-09-14 排查 DLP 时才被发现。

### 它怎么判断

只看**暂存区**里的内容（`git cat-file -p ":$f"`），不看工作区 —— 因为工作区是明文
并不代表 `git add` 进去的是明文。命中文件头 `E-SafeNet` 就拒绝提交并列出文件。

### 怎么启用

任选一种：

**方式 A：指向仓库里的钩子目录（推荐，钩子更新会自动生效）**

```sh
git config core.hooksPath scripts/git-hooks
```

注意 `core.hooksPath` 会**替换**整个 `.git/hooks` 目录，仓库里若以后新增别的钩子，
也要一起放进 `scripts/git-hooks/`。

**方式 B：拷贝到本地钩子目录（只影响本机）**

```sh
cp scripts/git-hooks/pre-commit .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit      # Windows 上可省略
```

**关闭**

```sh
git config --unset core.hooksPath   # 方式 A
```

### 临时绕过

确实需要提交一个密文文件（例如故意提交二进制资源）时：

```sh
git commit --no-verify
```

### 遇到拦截怎么办

1. **先确认是不是误报** —— 读前 64 字节看有没有 `E-SafeNet`：

   ```sh
   python -c "print(open(r'<path>','rb').read()[:64])"
   ```

2. **恢复明文** —— 基线一律取 `git show HEAD:<path>`（HEAD 里的内容是干净的），
   用 python 写回工作区，**紧接着立刻 `git add`**。python 写盘后有几秒明文窗口，
   够 git 把内容读进对象库；等 DLP 重新加密就来不及了。

3. **别用 `git checkout-index` / `git checkout -- <path>` 恢复** ——
   实测它写出来的文件 1 秒内就被加密，构建会直接挂掉。

### 根治办法

请 IT 把以下目录加入 DLP 排除清单，比任何钩子都管用：

- 本项目目录（至少 `build/`、`.gradle/`）
- `~/.gradle/caches/`
- 插件运行时的解包目录（Windows 默认 `%TEMP%\dshstudio-runtime`）
