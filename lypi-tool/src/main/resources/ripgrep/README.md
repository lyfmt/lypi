# Vendor ripgrep

ly-pi 的 `grep` 工具默认使用随包分发的 ripgrep 二进制，不要求用户机器预装 `rg`。

## 目录结构

```text
ripgrep/
  x86_64-linux/rg
  aarch64-linux/rg
  x86_64-macos/rg
  aarch64-macos/rg
  x86_64-windows/rg.exe
```

## 来源

- Upstream: https://github.com/BurntSushi/ripgrep
- Release: https://github.com/BurntSushi/ripgrep/releases
- Version: TODO: 固定实现时采用的 ripgrep release 版本。
- License: MIT OR Unlicense

## 更新要求

1. 只从官方 release 下载对应平台资产。
2. 校验官方 `.sha256` 文件。
3. 解压后仅提交 `rg` 或 `rg.exe`。
4. 在本文件记录版本、资产名和 sha256。

当前提交先落地 resolver 与测试；实际二进制资产在切换默认运行路径前必须补齐。
