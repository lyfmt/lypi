# Vendor ripgrep

ly-pi 的 `grep` 工具默认使用随包分发的 ripgrep 二进制，不要求用户机器预装 `rg`。

## 目录结构

```text
ripgrep/
  x86_64-linux/rg
  x86_64-linux/LICENSE-MIT
  x86_64-linux/UNLICENSE
  aarch64-linux/rg
  x86_64-macos/rg
  aarch64-macos/rg
  x86_64-windows/rg.exe
```

## 来源

- Upstream: https://github.com/BurntSushi/ripgrep
- Release: https://github.com/BurntSushi/ripgrep/releases/tag/15.1.0
- Version: 15.1.0
- License: MIT OR Unlicense

当前已提交资产：

| Platform | Asset | Release digest | Vendored binary sha256 | Size |
| --- | --- | --- | --- | --- |
| `x86_64-linux` | `ripgrep-15.1.0-x86_64-unknown-linux-musl.tar.gz` | `sha256:1c9297be4a084eea7ecaedf93eb03d058d6faae29bbc57ecdaf5063921491599` | `ebeaf56f8a25e102e9419933423738b3a2a613a444fd749d695e15eba53f71f2` | 5.2 MB |

## 更新要求

1. 只从官方 release 下载对应平台资产。
2. 校验官方 `.sha256` 文件。
3. 解压后仅提交 `rg` 或 `rg.exe`。
4. 在本文件记录版本、资产名和 sha256。

新增平台资产前，必须补齐同目录下的许可证文件或在本文件说明复用的许可证来源。
