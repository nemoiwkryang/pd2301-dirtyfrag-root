# iQOO Neo8 (PD2301 / V2301A) — DirtyFrag 临时 Root 完整归档

> 目标机：iQOO Neo8，PD2301 / V2301A，SM8475(taro)，Android 16 / SDK 36，
> 内核 `5.10.233-gki-g7c15030f5dfa`（构建 2026-05-12，GKI android12-5.10 KMI），
> BL 锁定，dm-verity，SELinux enforcing，system 补丁 2026-05-01 / vendor 补丁 2025-12-05。
>
> ⚠ **注意版本差异（这是本链能成立的关键，不是笔误）**：
> 设备内核是 `5.10.233-gki-g7c15030f5dfa`，而随包 ko 的 vermagic 是
> `5.10.246-gki-g8c3d0d7851ff SMP preempt mod_unload modversions vivo aarch64`。
> 二者 release 前缀不同，但 `MODVERSIONS` 下 `same_magic()` 只比较
> **第一个空格之后的部分**（`SMP preempt mod_unload modversions vivo aarch64`），
> 两者尾串完全一致，因此可加载。若尾串不同则会 `-ENOEXEC`。
>
> **结果：root 达成**（KernelSU `kernelsu.ko` late-load 成功，`/data/adb` 建立，
> 管理器显示「工作中 [越狱模式]」）。重启后失效（所有改动都在页缓存），需重跑。
>
> **本仓库不含成品 APK**。`artifacts/` 只保留构建出的 `libdfroot.so` / `libbootstrap.so`，
> 以及一个加载失败的自编 ko 作对照样本。APK 请按 §4.1 自行构建。

---

## 归档内容

```
.
├── README.md               ← 主文档：完整链路、踩坑、复现步骤
├── LICENSE                 ← 上游 MIT（OnePlus 16 Jailbreak 项目）
├── NOTICE                  ← 第三方组件许可（GPL / Apache 混合）
├── src/
│   ├── jni/                ← 核心 native 代码
│   │   ├── exp.c           ← 主体：页缓存写、patch_ko、patch_vivo_coredump、patch_hook
│   │   ├── libcxx.S        ← init hook shellcode（628B，4 marker + /dev/d2）
│   │   ├── elf_parser.c    ← 符号定位 + code cave 搜索（640B tail-page slack）
│   │   ├── splicehelper.c  ← helper 源码
│   │   ├── splicehelper    ← 1408B ET_EXEC 预编译 blob（必需，勿重编）
│   │   ├── vivo_coredump_shim ← 544B ET_EXEC shim（BLOCK-2 关键）
│   │   ├── include.inc     ← aarch64 syscall 常量 + 宏
│   │   ├── bootstrap.c     ← post-root bootstrap
│   │   ├── aes256.h        ← CBC 的 AES-256 实现
│   │   ├── ko_select.h     ← KMI 选择
│   │   └── ko/
│   │       └── dfroot-android12-5.10.ko  ← 官方 14,120B vivo ko（关键：用它）
│   ├── java/               ← Kotlin 源码
│   ├── res/                ← 资源
│   ├── AndroidManifest.xml
│   └── *.gradle.kts        ← gradle 配置
├── artifacts/
│   ├── libdfroot.so        ← 最终构建产物（52,512B）
│   └── libbootstrap.so
│   └── self-built-v2-FAILS.ko  ← 自编 ko 对照样本（加载失败，留档）
├── scripts/
│   ├── build-dfroot.sh     ← 手动 NDK 构建脚本
│   └── zero-versions.py    ← __versions 清零工具
└── kernel/
    └── device.config       ← 设备内核配置（IKCONFIG 提取）
```

---

## 1. 最终链路

```
DirtyFrag (CVE-2026-43284) —— 页缓存写原语（ESP AES-CBC 解密写回 splice 的 page cache）
  │
  ├─① splicehelper 1,408B ET_EXEC ──写入页缓存──> /apex/com.android.runtime/bin/crash_dump64
  │     （借 crash_dump 域读取 app 域读不到的 vendor_file）
  │
  ├─② 官方 vivo ko 14,120B ──写入页缓存──> /vendor/lib64/android.hardware.audio.common@4.0-util.so
  │     （vendor_file 标签，唯一被授权 module_load 的 target 类型）
  │
  ├─③ libc++.so shellcode 628B + trampoline 16B ──写入页缓存──> /system/lib64/libc++.so
  │     （劫持 std::ostream::sentry 构造）
  │
  ├─④ vivo_coredump shim 544B ──写入页缓存──> /system/bin/vivo_coredump
  │     （解 BLOCK-2 的关键，见 §3.4）
  │
  ▼
init (uid 0, tid 1) 触发 hook
  → /dev/df 一次性 mutex
  → clone worker → clone grandchild
  → grandchild: write /proc/self/attr/exec = u:r:vendor_modprobe:s0
  → execve /vendor/bin/insmod（toybox → toybox_vendor，vendor_toolbox_exec 标签）
  → finit_module(273) 加载 ② 的 ko
  → dfroot LKM init:
       selinux_state = false  → Permissive
       call_usermodehelper(...)
         ⚠ 被 CONFIG_STATIC_USERMODEHELPER 劫持到 /system/bin/vivo_coredump
         ✅ 但那里已被换成我们的 544B shim
         → 内核以 uid 0 + 全 capability 执行 shim
         → shim: execve(argv[0], argv, envp) 原样转发
         → 真 /system/bin/sh 以 root 运行:
              touch /dev/dfm0
              /data/user_de/0/df.root/ksud late-load --kmi android12-5.10
  → kernelsu.ko 加载 → /data/adb 建立 → ROOT
```

---

## 2. 关键产物

| 产物 | 大小 | sha256 前缀 | 说明 |
|---|---|---|---|
| `src/jni/ko/dfroot-android12-5.10.ko` | 14,120 | `b267c414…c23` | **官方 ko，用它** |
| `artifacts/self-built-v2-FAILS.ko` | 15,552 | `e892d82f…` | 自编对照，**加载失败** |
| `src/jni/vivo_coredump_shim` | 544 | — | BLOCK-2 关键 |
| `src/jni/splicehelper` | 1,408 | `977741e8…64a` | ET_EXEC，**勿重编** |
| 载体（设备上） | 19,600 | — | `/vendor/lib64/android.hardware.audio.common@4.0-util.so` |
| `artifacts/libdfroot.so` | 52,512 | `3e856cef…b24a` | 最终构建 |

官方 ko 提取（`app-release.apk` 内 `libdfroot.so` 的 `0x12d0..0x49f8`）：

```python
import zipfile
d = zipfile.ZipFile('app-release.apk').read('lib/arm64-v8a/libdfroot.so')
open('dfroot-android12-5.10.ko','wb').write(d[0x12d0:0x49f8])   # 14120 bytes
```

> **坑**：按 `e_shoff + e_shnum*shentsize` 算只到 12,386B，但那样会截断节头表，
> `elf_validity_check` 会拒。完整长度应取到下一个内嵌 blob 起点（`0x49f8`）= 14,120B。

---

## 3. 踩过的坑

### 3.1 splicehelper 被误放成 ko（BLOCK-1）

`jni/splicehelper` 一度是 12,688B 的 **ET_REL ko**（`vermagic=5.10.246…`），不是 helper。
真正的 helper 是 **1,408B ET_EXEC**（entry `0x204458`，4 program headers，自洽）。
后果：`patch_helper()` 把 ko 写进 crash_dump64 → 它不再是可执行 helper →
`read_vendor_content()` 拿不到 16B → 后续全部静默失败。

### 3.2 自编 ko 被 strip（真正卡了四轮的根因）

`dfroot-diag.ko` 只有 18 个节，**没有 `.symtab`、没有 `.strtab`、没有任何 `.rela.*`**。
`setup_load_info()`（`kernel/module.c:3242`）：

```c
pr_warn("%s: module has no symbols (stripped?)\n", ...);
return -ENOEXEC;
```

只进 klog、shell 域读不到 → 表现为「execve 成功、零 avc、模块静默不加载」。

修：用内核 build 系统原样重编（不 strip）→ 删 debug/note/plt 但**保留 symtab/rela** → 清零 `__versions`。
**但 v2 仍失败**。

### 3.3 A/B 实验：官方 ko 一次定音

| | 官方 ko | 自编 v2 |
|---|---|---|
| 大小 | 14,120B | 15,552B |
| `__versions` | **0 字节节** | 1088B 全零条目 |
| 模块名 | `dfroot` | `dfroot_diag` |
| vermagic | `5.10.246-gki-…vivo` | `5.10.270-gki-…dirty … vivo` |
| 结果 | **加载成功** | 失败 |

结论：公共路径（载体 / execve / finit_module / vermagic 尾串 / 页缓存写 / SELinux / splice 桥）
**全部自洽**，问题只在 ko 自身字段。**直接用官方 ko。**

### 3.4 BLOCK-2：umh 被 STATIC_USERMODEHELPER 劫持

```
CONFIG_STATIC_USERMODEHELPER=y
CONFIG_STATIC_USERMODEHELPER_PATH="/system/bin/vivo_coredump"
```

`kernel/umh.c:369-373`：

```c
#ifdef CONFIG_STATIC_USERMODEHELPER
	sub_info->path = CONFIG_STATIC_USERMODEHELPER_PATH;
#else
	sub_info->path = path;
#endif
```

→ 所有 `call_usermodehelper` 的 path 被**无条件覆盖**，`modprobe_path` 完全失效
（设备 `/proc/sys/kernel/modprobe` 也是空值，init.rc 清空）。
所以改 `/sbin/modprobe` / `request_module` 这条路**行不通**。

**解法**：用页缓存写把 `/system/bin/vivo_coredump` 本身换成 544B shim
（`execve(argv[0],argv,envp)` 原样转发）。

### 3.5 两条「不可行」结论为何被推翻

两条独立研究线都判定 vivo_coredump 通道不可行：

1. `modprobe_path` 为空 → `kmod.c:138` 直接 `-ENOENT` → 走不到 umh
   → **不成立**：dfroot 自己的 init 就调 umh，不需要 `request_module`
2. `neverallow kernel (all) (file (execute_no_trans))`
   → **permissive 下只记日志、不拒绝**，而 dfroot init 是**先**写 `selinux_state=false`、**后**调 umh

### 3.6 DE / CE 路径不一致（最后一个断点）

官方 ko 硬编码 `/data/user_de/0/df.root/ksud`（**DE**），
但 app 的 `stageAssets()` 写到 `filesDir.parentFile` = `/data/user/0/df.root`（**CE**）。

root sh 报 `inaccessible or not found` → ksud 没起来。
**修**：`run-as df.root cp` 把 ksud 从 CE 复制到 DE（DE 持久，reboot 不丢）。

### 3.7 其他被排除的方向

| 项 | 结论 |
|---|---|
| `MODULE_SIG` / lockdown | 设备 IKCONFIG 实测**未设置** → 排除 |
| `MODULE_INIT_IGNORE_VERMAGIC` | `CONFIG_MODULE_FORCE_LOAD=n` → `try_to_force_load()` 直接 `-ENOEXEC`，**结构性无效** |
| erofs 压缩导致 kernel_read 绕过页缓存 | 排除：`generic_file_splice_read` 与 `__kernel_read` 走同一 `read_iter` |
| 改 /system 载体 | 会丢掉 `module_load` 的 target 类型（`system_file` 不在授权列表） |
| `run-as` 免装包 | SELinux 结构性不可行：`neverallow … app_data_file (file (execute_no_trans))`，app 只能执行 `nativeLibraryDir`（`apk_data_file`），run-as 写不进那里 |
| io_uring / net_sched / nf_tables / KVM / dma_heap / userns | 全部不可达（seccomp 白名单 / config 未设 / 无 CIL allow） |
| `crash_dump.arm64.policy` 拦 execve | 假线索：AOSP `crash_dump.cpp:806` 有 `// TODO: Use seccomp to lock ourselves down.`，策略**未启用** |
| GhostLock (CVE-2026-43499) | 帧几何不可行（两次 panic 实证） |
| CVE-2026-98260 | 6.18 底座硬编码（k_itimer/cred 偏移、rb_alloc 地址全写死），5.10 等于重写 |

---

## 4. 可复现步骤

### 4.1 构建

```bash
# 依赖：Android NDK r27c
export NDK=/path/to/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/bin

# 1. 确认 src/jni/splicehelper 是 1408B ET_EXEC（不要重建！）
readelf -h src/jni/splicehelper | grep -E 'Type|Entry'
#   期望: Type: EXEC, Entry point 0x204458

# 2. 构建 libdfroot.so / libbootstrap.so
cd src/jni
$NDK/aarch64-linux-android33-clang -O2 -fPIE -pie -std=gnu11 -Wall \
    -I. -DAPI=33 exp.c libcxx.S elf_parser.c \
    -o ../../artifacts/libdfroot.so -llog -lm -Wl,-z,max-page-size=16384

# 3. 打包 APK（gradle 或手动重打包 + zipalign + v2 签名）
```

### 4.2 一次性准备（装 APK）

```bash
S=<YOUR_DEVICE_SERIAL>   # adb devices 里第二列就是
# 注：本仓库不含 app-debug.apk，需自行构建：
#   cd src && gradle assembleDebug      （或按 §4.1 手动打包）
adb -s $S install -r app-debug.apk
#  vivo 安装器接管：P1 勾选框单击 → 等 >3s（3000ms 防抖）
#  → dump 确认 checked=true → 点「继续安装」
#  若出现 P2 身份验证页，必须停下
```

### 4.3 每轮复现

```bash
S=<YOUR_DEVICE_SERIAL>   # adb devices 里第二列就是

# 1. 保证 DE 路径有 ksud（首次之后持久，通常可跳过）
adb -s $S shell 'run-as df.root sh -c "
  cp /data/user/0/df.root/ksud /data/user_de/0/df.root/ksud &&
  chmod 755 /data/user_de/0/df.root/ksud"'

# 2. reboot 清 /dev/df mutex（也恢复所有页缓存改动）
adb -s $S reboot
# USB 重枚举可能慢到 6.5 分钟，耐心等 boot_completed=1

# 3. 触发
adb -s $S shell 'input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard'
adb -s $S shell 'am start -W -n df.root/com.iqooneo8.dirtyfrag.MainActivity'
#   PM 扫描延迟可能报 "Activity class {...} does not exist."，重试即可
adb -s $S shell 'uiautomator dump'   # 确认「一键越狱」存在
adb -s $S shell 'input tap 630 1103' # 单击一次
sleep 60
```

### 4.4 验证 root

```bash
adb -s $S shell 'grep -iE "kernelsu|dfroot" /proc/modules'
# 期望: kernelsu 212992 1 - Live (O)  /  dfroot 16384 0 - Live (O)

adb -s $S shell 'ls /data/adb 2>&1'
# 期望: Permission denied（=存在，root 0700）
# 对照: ls /data/definitely_not_here_xyz → No such file or directory

adb -s $S shell 'ls -l /data/local/tmp/k'
# 期望: -rw-r--r-- 1 root root 0   ← ksud late-load 静默成功
```

**注意**：`getenforce` 会显示 `Enforcing` —— 这是 **KernelSU 加载后自己恢复的**，不是失败。
`su -c id` 从 ADB shell 不可用（`u:r:shell:s0` / uid 2000 无权访问 `/data/adb/ksu/bin/su`），
需要在 KernelSU 管理器 UI 里给目标 app（或 Shell）授权。

---

## 5. 关键约束备忘

- **每轮需 reboot**：`/dev/df` 是一次性 mutex；页缓存改动不落盘，reboot 全部恢复
- **必须装 APK**：`run-as` 免装包不可行（见 §3.7）
- **shellcode ≤ 640B**：`elf_parser.c` 的 `find_code_cave()`
  算 `slack = 4096 - (0xf6d80 & 0xfff) = 640`
  （`exp.c` 的 `shell_orig[1024]` 只是 cleanup 保存缓冲，**不是放置上限**）
- **载体必须是 `vendor_file` 标签**：全策略只有 5 条 `module_load` allow，
  且 `system_dlkm` 分区在设备上不存在（死规则）
- **`setenforce` 全封死**：`security (setenforce)` allow = 0 条，
  `neverallow (all) kernel (security (setenforce))`

---

## 6. 设备基线

| 项 | 值 |
|---|---|
| 型号 | PD2301 / V2301A，iQOO Neo8 |
| SoC | SM8475 (taro) |
| 系统 | Android 16 / SDK 36，OriginOS，`PD2301_A_16.3.15.0.W10.V000L1` |
| 指纹 | `vivo/PD2301/PD2301:16/BP2A.250605.031.A3/compiler260616161318:user/release-keys` |
| 内核 | `5.10.233-gki-g7c15030f5dfa`，构建 `2026-05-12` |
| 安全补丁 | system `2026-05-01` / vendor `2025-12-05` |
| 加固 | CFI_CLANG、SCS、INIT_ON_ALLOC、SLAB_FREELIST_HARDENED/RANDOM、SHUFFLE_PAGE、VMAP_STACK、HARDENED_USERCOPY、PAN/UAO、PANIC_ON_OOPS、KASAN_HW_TAGS(cmdline off)、KFENCE、DEBUG_LIST |
| 未启用 | MODULE_SIG、LOCKDOWN_LSM、NF_TABLES、USER_NS |

---

## 许可

本项目基于 **OnePlus 16 Jailbreak**（MIT License）派生，详见 `LICENSE`。
内含第三方组件的许可声明见 `NOTICE`（KernelSU/KernelSU-Next/BakaSU 等为 GPL-2.0，
Miuix/Shizuku/COUI 等为 Apache-2.0）。
