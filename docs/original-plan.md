# iQOO Neo8 (V2301A / PD2301) 临时 root

> **2026-11-04 方向调整**：原计划移植 CVE-2026-98260，公开生态无 5.10 适配；
> 设备上已实机跑通 **DirtyFrag / CVE-2026-43284**（page-cache 写）。
> 现方向以 DirtyFrag 为主，98260 部分仅作历史资料保留。
> 分析见 `docs/rea-dirtyfrag.md`。

## 原目标（历史）

在 **iQOO Neo8（PD2301 / V2301A，SM8475，OriginOS 6 / Android 16，内核 `5.10.246-gki-g8c3d0d7851ff`，BL 锁定）** 上，
用 CVE-2026-98260（exec/POSIX-cpu-timer UAF）拿到 **临时 root（uid 0，重启失效）**，并加载 KernelSU 系 LKM。

底座：[Yunnijian/OnePlus16-Jailbreak](https://github.com/Yunnijian/OnePlus16-Jailbreak)（MIT，6.18.21 / 6.12.69 两套 98260 链）。
本仓库只保留与 5.10 适配相关的结构，不承诺跨机型通用。

## 设备基线（2026-11-04 实测）

| 项 | 值 |
|---|---|
| 型号 | PD2301 / V2301A，iQOO Neo8，SM8475（taro） |
| 系统 | Android 16 / OriginOS 6，`PD2301_A_16.3.17.0.W10` |
| 内核 | `5.10.246-gki-g8c3d0d7851ff`，编译 2026-08-13，4K 页 |
| 补丁级别 | 2026-08-01（CVE 2026-10-06 公开，晚于内核） |
| 启动 | BL 锁定，`verifiedbootstate=green`，dm-verity enforcing |
| SELinux | Enforcing |
| 加固 | CFI_CLANG、SHADOW_CALL_STACK、INIT_ON_ALLOC_DEFAULT_ON、SLAB_FREELIST_HARDENED/RANDOM、SLUB_MIRROR、PANIC_ON_OOPS=y、DEBUG_LIST、BUG_ON_DATA_CORRUPTION、VMAP_STACK |
| KASAN | 编译了 `KASAN_HW_TAGS`，但 cmdline `kasan=off`、`slub_debug=-` |
| 可用的口子 | shell 可读 `/proc/slabinfo`；`/sys/kernel/tracing/events/kmem/rss_stat/trigger` 可写（0666）；`trace` 可读写；`perf_event_paranoid=-1`；`unprivileged_bpf_disabled=0` |
| 不可用 | `/proc/kallsyms` 拒绝；`/sys/kernel/btf/vmlinux` 不存在；`events/*/enable` 拒绝；`kprobe_events` 拒绝；`available_filter_functions` 不存在 |
| 厂商反 root | `/proc/modules` 有 `vr`（permanent, Live），挂 `android_rvh_commit_creds` |

## 固件

- 来源：vivo 官方 OTA 直链（`sysuptxdl.vivo.com.cn`），版本与设备完全一致。
- 已下载：`firmware/PD2301_A_16.3.17.0.W10.zip`（8,541,785,123 B，AB 包，含 `payload.bin`）。
- 用途：提取 `boot.img` / `vendor_boot.img` / `vendor_dlkm`，从 kernel Image 重建 vmlinux 符号表。

## 里程碑

### M0 · 差异分析（不碰设备）
- 通读底座 `exploit/src/t98260_plant.c`、`t61269_plant.c`、`exploit/rust-t98260/src/main.rs`。
- 逐项列出底座对 6.18 的硬编码假设（符号、偏移、tracefs 路径、SLUB 参数、exec 路径）。
- 对照 5.10.246 源码，产出适配清单和需要从 vmlinux 提取的符号/偏移表。
- 交付：`docs/port-analysis-5.10.md`。

### M1 · 离线符号（不碰设备）
- payload.bin → boot/vendor_boot → kernel Image。
- `vmlinux-to-elf` 用 kallsyms 重建符号；拿 `task_struct` / `cred` / `k_itimer` / `posix_cputimers` / `selinux_state` 等。
- 交付：`offsets/pd2301-5.10.246.json` + `docs/symbols.md`。

### M2 · 触发与泄漏（真机，允许 panic）
- 5.10 的 `de_thread()` / `exec_mmap()` / `exit_itimers()` 路径适配。
- 用 `rss_stat/trigger` 启用 `kmem` 事件，从 `trace_pipe` 拿 `k_itimer` 地址和 KASLR slide。
- 交付：`poc`（只触发，不写）在备用机验证。

### M3 · 写原语与回收
- 5.10 的 `timerqueue_del()` / `rb_erase()` 行为与 6.x 的差异。
- `posix_timers_cache` 大小/order 从 `/proc/slabinfo` 实测。
- 交付：`plant` 能稳定把零写落到 `selinux_state - 7`。

### M4 · cred 与 vivo 反 root
- cred 零写 / 指针写；`vr` 的 `android_rvh_commit_creds` hook 处理。
- 交付：uid 0 shell（`u:r:shell:s0` 或 `u:r:ksu:s0`）。

### M5 · LKM 与 App
- LKM 必须按 5.10.246 / SM8475 自编（不能只改 vermagic）。
- App 复用底座 UI，但链选择、资产、清理逻辑要改。

## 风险

- 每次失败基本是 kernel panic + 自动重启；`PANIC_ON_OOPS=y`。
- BL 锁定，无持久化；`vr` 可能主动重启。
- 主力机不建议直接跑；用备用机/可接受重启的设备。

## 工作方式

- 青岚负责规划、审 diff、验收；实现交给 codex。
- 所有设备端实验先跑只读/可恢复步骤；destructive 前先确认。
