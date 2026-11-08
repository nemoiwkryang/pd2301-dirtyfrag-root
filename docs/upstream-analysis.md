# REA：`一键提权-v3.apk` 分析报告

- 样本：`一键提权-v3.apk`（第三方分发的 DirtyFrag 构建）
- SHA-256：`f4b3234a9b4374faa1aaa29f885373c59ffaeedc54ab3999d6eacf3265d6b105`
- 大小：2.5 MB，647 个 zip 条目
- 分析方式：静态（androguard 字节码、ELF 解析、字符串、AndroidManifest），未执行 APK

## 1. 身份

| 项 | 值 |
|---|---|
| 包名 | `df.root` |
| versionName / Code | `1.15` / `32` |
| minSdk / targetSdk | 32 / 37 |
| 入口 | `df.root.MainActivity` |
| 接收器 | `df.root.BootReceiver`（BOOT_COMPLETED） |
| 权限 | `INTERNET`、`QUERY_ALL_PACKAGES`、`RECEIVE_BOOT_COMPLETED`、`WAKE_LOCK` |

包名、类名（`df.root.ExploitRunner`、`BootReceiver`、`SuManagerEntry`）、prefs 键（`expert_mode`、
`auto_soft_reboot`、`su_manager`）与 **DirtyFrag** 上游完全一致。

`MainActivity.checkForAppUpdate()` 请求：

```
https://api.github.com/repos/mitschud/DirtyFrag/releases/latest
```

→ 该 APK 是 **mitschud/DirtyFrag**（GPL-2.0-only，CVE-2026-43284）的构建，不是 CVE-2026-98260。

## 2. 这不是 98260

DirtyFrag 利用的是 **CVE-2026-43284（page-cache 写）**，与 POSIX timer UAF 无关：

> Android 内核把 AES-CBC ESP 包直接解密进 `splice()` 打开的文件的 page cache。
> 构造 `IV = AES_ECB_DEC(key, current) XOR desired`，即可在无写权限、不触发 COW 的情况下
> 覆盖共享库中任意 16 字节对齐块。

链（README + 二进制字符串互相印证）：

1. `IpSecManager` 分配 `UdpEncapsulationSocket` + SPI + AES-CBC/HMAC-SHA256 ESP transform；
2. 用 page-cache 原语把 splicehelper 写进 `/apex/com.android.runtime/bin/crash_dump64`；
3. 借 `crash_dump` 域把 `dfroot.ko` 写进 `vendor_file` 标注的文件；
4. 在 `libc++.so` 的 `std::ostream::sentry::sentry()` 打 shellcode，init（uid 0 / tid 1）时触发，
   建 `/dev/df` 一次性互斥后 `insmod`；
5. `dfroot.ko` init：SELinux permissive、kprobe 绕 DEFEX、`call_usermodehelper` 跑 bootstrap；
6. bootstrap：读 app 的 device-protected 设置、继承 zygote 环境、分区设只读、启动所选管理器的
   `ksud`（late-load）。

字节码侧证据：`ExploitRunner.run()` 里 `IpSecManager.openUdpEncapsulationSocket()`、
`allocateSecurityParameterIndex()`、`IpSecTransform.Builder`、`setEncryption("cbc(aes)")`、
`setIpv4Encapsulation()`，然后 `ProcessBuilder` 调 `libdfroot.so --encap-port … --sender-port … --spi … --aes-key …`。
`stageAssets()` 把所选管理器 APK 里的 `libksud.so` 复制为 `ksud`，把 `libbootstrap.so` 复制为 `bootstrap`，
放到 `filesDir.getParentFile()`（`/data/user_de/0/df.root/`）。

## 3. 内置模块

`libdfroot.so`（123,720 B）里顺序内嵌 11 个 ELF：

| # | offset | vermagic |
|---|---|---|
| 00 | 0x0 | （非 ko，4,960 B，疑似宿主 so 自身或壳） |
| 01 | 0x1360 | `5.10.233-gki-g3f8f6548ec82 …` |
| 02 | 0x4460 | `5.10.245 …` |
| 03 | 0x7250 | `5.15.202-android13-5.15.202_r00-dirty …` |
| 04 | 0xa1d8 | `5.15.202-dirty …` |
| 05 | 0xd160 | `6.1.166-dirty …` |
| 06 | 0xf1d0 | `6.6.127-4k-g46a034eca005-dirty …` |
| 07 | 0x11400 | `6.12.76-4k …` |
| 08 | 0x13810 | `6.18.32-4k-g47cc22554442-dirty …` |
| 09 | 0x15c28 | （20,472 B，非 ko） |
| **10** | **0x1ac20** | **`5.10.246-gki-g8c3d0d7851ff SMP preempt mod_unload modversions vivo aarch64`** |

`lkm-10` 与本机 `uname -r` 完全一致，`modinfo` 为 `name=dfroot`、`license=GPL`、
`depends=`，导出 `init_module` / `cleanup_module`，仅依赖 `register_kprobe` / `unregister_kprobe` / `printk`。
即：**这个包内置了为 PD2301 / 5.10.246 精确编译的 DFRoot LKM**，用户实机跑通与此吻合。

`dfroot.ko` 内可见的 usermodehelper 命令：

```
touch /dev/dfm0; rmmod oplus_secure_harden 2>/dev/null; rmmod oplus_security_keventupload 2>/dev/null; rmmod oplus_security_guard 2>/dev/null; exit 0
touch /dev/dfm0; /data/user_de/0/df.root/ksud late-load --kmi android12-5.10 >/data/local/tmp/k 2>&1; exit 0
```

（前一条来自 OPPO/一加分支的残留，本机不适用；后一条是实际的 ksud late-load。）

## 4. 行为面

- `BootReceiver`：开机检查 `/dev/df`，存在则跳过；`expert_mode` 关闭时跳过自动提权；
  运行失败时 `setComponentEnabledSetting` 自禁用，避免 bootloop。
- `INTERNET` 仅用于 GitHub Releases 版本检查（`checkForAppUpdate`），未见上传。
- 未发现硬编码的远程地址、上传逻辑或第三方 SDK（字节码中只有 GitHub API）。
- 风险仍在：这是第三方构建、无签名校验链、GPL 源码可对照但**此 APK 非官方 release 哈希**；
  建议对照上游源码自行构建，或至少核对 `libdfroot.so`/`lkm-10` 与上游构建产物。

## 5. 对本项目的结论

1. **不要继续 98260 移植**：5.10 没有公开适配，而本机已有一条跑通的 CVE-2026-43284 链。
2. 我们的项目应改为 **DirtyFrag 的 PD2301 定制构建**：上游 `mitschud/DirtyFrag`（GPL-2.0-only）
   已支持 `android12-5.10` KMI 的 LKM 构建（CI `LKMs / android12-5.10`），
   需要补的是本机 vermagic（`5.10.246-gki-g8c3d0d7851ff … vivo`）和 `lkm-10` 的构建/校验。
3. 现有 APK 可作为「已验证的参考产物」：`lkm-10` 可直接作为对照，或反推其编译配置。
4. 许可证：上游 GPL-2.0-only，派生项目必须同许可，不能吸收 GPL-3.0 代码。

## 6. 未完成

- `libdfroot.so` 的前置 ELF patch 逻辑（`read_vendor`、`patch_file_cbc`、`PACIASP/BTI` 处理）
  尚未反汇编到函数级；需要 Ghidra/rizin。
- `lkm-09`（20 KB）未识别。
- 未验证 APK 签名与上游 release 的一致性。
