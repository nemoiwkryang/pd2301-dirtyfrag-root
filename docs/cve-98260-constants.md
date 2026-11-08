# t98260_plant.c 常量清单

来源：`exploit/src/t98260_plant.c`。使用正则逐行检索（本机无 `rg`，使用 PowerShell `Select-String` 实现 grep 式匹配）；行号均对应源文件。仅整理文本，不分析运行逻辑。

## #define 常量

| 名称 | 原始定义值（含原注释） | 行号 |
| --- | --- | --- |
| `_GNU_SOURCE` | `1` | 8 |
| `TRACE` | `"/sys/kernel/tracing"` | 34 |
| `TRIGGER` | `TRACE "/events/kmem/rss_stat/trigger"` | 35 |
| `RSS_ENABLE` | `TRACE "/events/kmem/rss_stat/enable"` | 36 |
| `PAGE_OFFSET` | `UINT64_C(0xffffff8000000000)` | 37 |
| `TIMER_LINK` | `UINT64_C(0xffffffc0803d7880) /* do_timer_create+0xb8 */` | 39 |
| `RB_ALLOC_LINK` | `UINT64_C(0xffffffc0806b0a50) /* rb_alloc */` | 40 |
| `RB_ALLOC_LEN` | `0x418U` | 41 |
| `SELINUX_LINK` | `UINT64_C(0xffffffc082979630)` | 42 |
| `LINK_USER_NS` | `UINT64_C(0xffffffc0826f10d8)` | 43 |
| `LINK_ROOT_USER` | `UINT64_C(0xffffffc0826f1388)` | 44 |
| `LINK_GROUPS` | `UINT64_C(0xffffffc0826f2a48)` | 45 |
| `LINK_UCOUNTS` | `UINT64_C(0xffffffc0826f2f08)` | 46 |
| `KERNEL_RELEASE` | `"6.18.21-android17-5-gf957accaf563-ab15926176-4k"` | 48 |
| `KITIMER_NODE` | `0xe0` | 49 |
| `KITIMER_SIZE` | `0x180` | 50 |
| `NTIMER` | `2048` | 51 |
| `ARMED_INDEX` | `1024` | 52 |
| `CAPTURE_EXPECT` | `NTIMER` | 53 |
| `ROOTD_SOCK` | `"/data/local/tmp/t98260-root.sock"` | 54 |
| `ROOTD_RETIRE` | `"/data/local/tmp/t98260-retire"` | 55 |
| `ROOTD_EXIT_MARKER` | `"__ROOTD_EXIT_AFTER__"` | 56 |
| `SPRAY_PAIRS` | `4096` | 63 |
| `VICTIM_CPU` | `1` | 64 |
| `NVMA` | `50000` | 427 |
| `HELPER_STACK` | `(1 << 20)` | 428 |
| `TASK_LINK` | `UINT64_C(0xffffffc08020f904) /* copy_process+0x140 */` | 622 |
| `TASK_ALLOC_BYTES` | `5248` | 623 |
| `MAX_TIMER_REC` | `2200` | 624 |
| `MAX_REQ_REC` | `4096` | 674 |
| `FULLCAP` | `UINT64_C(0x1ffffffffff)` | 1183 |
| `TASK_CRED` | `0x908` | 1184 |
| `KIT_MODE_TILE` | `0` | 1185 |
| `KIT_MODE_CRED` | `1` | 1186 |

## 写死的 64 位地址

| 名称 | 地址字面量 | 行号 |
| --- | --- | --- |
| `PAGE_OFFSET` | `0xffffff8000000000` | 37 |
| `TIMER_LINK` | `0xffffffc0803d7880` | 39 |
| `RB_ALLOC_LINK` | `0xffffffc0806b0a50` | 40 |
| `SELINUX_LINK` | `0xffffffc082979630` | 42 |
| `LINK_USER_NS` | `0xffffffc0826f10d8` | 43 |
| `LINK_ROOT_USER` | `0xffffffc0826f1388` | 44 |
| `LINK_GROUPS` | `0xffffffc0826f2a48` | 45 |
| `LINK_UCOUNTS` | `0xffffffc0826f2f08` | 46 |
| `TASK_LINK` | `0xffffffc08020f904` | 622 |

`FULLCAP` 的 `UINT64_C(0x1ffffffffff)` 是掩码，列于宏表，不计入地址。

## 内核版本字符串

| 文本 | 出现位置 | 行号 |
| --- | --- | --- |
| `6.18.21` | 文件头注释中的版本文本 | 3 |
| `6.18.21-android17-5-gf957accaf563-ab15926176-4k` | `KERNEL_RELEASE` 字符串 | 48 |
| `android17-6.18` | `kmi` 默认字符串 | 1605 |

## sysfs 路径

按 `/sys/` 前缀收录，包括该目录下的 tracing、SELinux 挂载路径；展开 `TRACE` 字符串拼接，保留 `%s`、`%d` 动态占位符。同一路径合并列出所有直接字面量或拼接出现的行号，不重复统计单独的宏引用。

| 路径或路径模板 | 行号 |
| --- | --- |
| `/sys/kernel/tracing` | 34 |
| `/sys/kernel/tracing/events/kmem/rss_stat/trigger` | 35 |
| `/sys/kernel/tracing/events/kmem/rss_stat/enable` | 36 |
| `/sys/kernel/tracing/trace` | 129 |
| `/sys/kernel/tracing/events/%s/enable` | 147 |
| `/sys/kernel/tracing/per_cpu/cpu%d/trace_pipe` | 256、1204 |
| `/sys/devices/system/cpu/present` | 275 |
| `/sys/fs/selinux/enforce` | 305 |
| `/sys/kernel/tracing/per_cpu/cpu0/trace_pipe_raw` | 337、678 |
| `/sys/kernel/tracing/per_cpu/cpu%d/trace_pipe_raw` | 663 |
| `/sys/kernel/tracing/tracing_on` | 853、858、867、913、918、922、1137、1142、1147、1413、1416、1436、1486、1491、1495、1502、1521、1525 |
| `/sys/kernel/tracing/buffer_size_kb` | 856、916、1138、1412、1439、1489、1520 |
