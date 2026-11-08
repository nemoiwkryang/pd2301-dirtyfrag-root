> **来源说明**：本文是上游项目（Yunnijian/OnePlus16-Jailbreak，MIT License）
> README 的原文保留，仅作对照参考，不是本项目文档。
> 上游 libdfroot.so 内置 11 个 KMI 的 ko，本归档**仅保留 android12-5.10 一支**（14,120B）。

---

# iQOO Neo8 (V2301A / PD2301) 一键越狱

在 **iQOO Neo8（PD2301 / V2301A，SM8475，Android 16 / OriginOS 6，内核 `5.10.246-gki-g8c3d0d7851ff`）** 上，
通过 **CVE-2026-43284（DirtyFrag）** 的 page-cache 写原语加载内核模块，拿到 **临时 root**。

- App UI 来自 [Yunnijian/OnePlus16-Jailbreak](https://github.com/Yunnijian/OnePlus16-Jailbreak)（MIT，Compose/COUI 三 tab）
- 利用链来自 [mitschud/DirtyFrag](https://github.com/mitschud/DirtyFrag)（GPL-2.0-only，fork 自 [diabl0w/DFRoot](https://github.com/diabl0w/DFRoot)）
- 设备端二进制 `libdfroot.so` / `libbootstrap.so` 取自实机验证过的第三方 DirtyFrag 构建（`df.root` 1.15），
  其中 `dfroot.ko` 的 vermagic 精确匹配本机内核

> 临时 root：模块、su 和已加载的模块在重启后全部消失，重启即回到干净状态，也是失败后的恢复方式。

## 适用设备

| 项 | 值 |
|---|---|
| 机型 | iQOO Neo8 / PD2301 / V2301A（SM8475） |
| 系统 | Android 16 / OriginOS 6 |
| 内核 | `5.10.246-gki-g8c3d0d7851ff`（KMI android12-5.10，4K 页） |
| Bootloader | 锁定即可，不需要解锁 |

`libdfroot.so` 内置多个 KMI 的 `dfroot.ko`（5.10.233 / 5.10.245 / 5.15.202 / 6.1.166 / 6.6.127 / 6.12.76 / 6.18.32
以及本机的 5.10.246-vivo），由 `exp.c` 的 `select_ko_image()` 按 `uname -r` 与 `androidNN` 选择。
其它内核版本能否使用取决于对应 vermagic 的 ko 是否存在，未验证。

## 原理

1. App 通过 `IpSecManager` 申请 `UdpEncapsulationSocket`、SPI，并建立 AES-CBC/HMAC-SHA256 的 ESP transform；
2. 内核把 ESP 包直接解密进 `splice()` 打开的文件的 page cache，构造
   `IV = AES_ECB_DEC(key, current) XOR desired` 即可覆盖共享库中任意 16 字节对齐块（无写权限、不触发 COW）；
3. 用该原语把 splicehelper 写进 `crash_dump64`，借 `crash_dump` 域读 `vendor_file`；
4. 把 `dfroot.ko` 写进 vendor 文件，在 `libc++.so` 的 `std::ostream::sentry` 打 shellcode，init（uid 0/tid 1）时 `insmod`；
5. `dfroot.ko` 设 SELinux permissive、kprobe 绕过 DEFEX、`call_usermodehelper` 启动 bootstrap；
6. bootstrap 启动所选管理器的 `ksud late-load --kmi android12-5.10`。

## 使用

1. 安装一个 **带 `libksud.so`** 的 root 管理器：
   - [KernelSU](https://github.com/tiann/KernelSU/releases/latest)
   - [KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next/releases/latest)
   - [BakaSU](https://github.com/Baka-SU/BakaSU/releases/latest)
   - [SukiSU Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra/releases/latest)
   - [FinaSU](https://github.com/LyraVoid/FinaSU/releases/latest)
2. 安装本 App，打开，在「ROOT管理器」里选择刚装的管理器；
3. 点「一键越狱」，等待日志出现 `KernelSU module loaded`；
4. 到管理器里授权 root。

不需要 Shizuku / Stellar，App 以自身 UID 运行整个利用链。

## 构建

环境：JDK 21、Android SDK 37（`platforms;android-37.0`、`build-tools;37.0.0`）。
不需要 NDK —— `libdfroot.so` / `libbootstrap.so` 是预编译产物，位于 `app/app/src/main/jniLibs/arm64-v8a/`。

```sh
cd app
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
# 或 assembleRelease（需要 keystore.properties）
```

产物：`app/app/build/outputs/apk/debug/app-debug.apk`。

CI（`.github/workflows/build.yml`）在 push / PR / tag 时构建 debug 与 release，并上传 artifact；打 tag 时附加到 Release。

## 目录

```
app/                                  Gradle 工程根
  app/src/main/java/com/iqooneo8/dirtyfrag/
    MainActivity.kt                   三 tab UI（首页/日志/历史）
    DirtyFragRunner.kt                IpSec + stageAssets + 执行 libdfroot.so
    IReporter.kt                      日志回调
    Privileged.kt                     Shizuku/Stellar 封装（UI 保留，链路未使用）
    HistoryStore.kt                   运行历史
  app/src/main/jniLibs/arm64-v8a/     libdfroot.so / libbootstrap.so
  app/src/main/assets/manual.md       使用手册
.github/workflows/build.yml           CI
docs/rea-dirtyfrag.md                 第三方 APK 的逆向分析记录
PLAN.md                               项目背景与阶段记录
```

## 已知限制

- 仅对 `5.10.246-gki-g8c3d0d7851ff` 做过链路匹配；其它内核请先核对 `dfroot.ko` 的 vermagic。
- 设备端 `.so` 为第三方构建，未从源码重编；介意可对照上游 `exp.c` 自行构建。
- 失败可能导致内核 panic / 重启；不要在不可恢复的设备上试。
- 反 root：vivo 的 `vr` 模块（`android_rvh_commit_creds`）仍在内核里，模块加载后是否触发检测未做完整验证。

## 致谢与许可

- UI：[Yunnijian/OnePlus16-Jailbreak](https://github.com/Yunnijian/OnePlus16-Jailbreak)（MIT）
- 利用链：[mitschud/DirtyFrag](https://github.com/mitschud/DirtyFrag)（GPL-2.0-only）、[diabl0w/DFRoot](https://github.com/diabl0w/DFRoot)
- 参考：[lsposed/lspromise](https://github.com/lsposed/lspromise)、[polygraphene/DFReroot](https://github.com/polygraphene/DFReroot)、[combeng6th/DirtyInit](https://github.com/combeng6th/DirtyInit)
- UI 代码沿用 MIT；与 DirtyFrag 相关的部分遵循 **GPL-2.0-only**。两者合并后，整体按 GPL-2.0-only 分发。

仅用于自有设备的研究与 root 管理。
