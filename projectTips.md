# 项目知识笔记

## CPU 和 ysyxSoC 的关系

CPU 只需要通过 AXI4 Master 接口往正确的地址发读/写请求，剩下的全部由 ysyxSoC 处理。

```
CPU (ysyx_23060000)
    │
    │  AXI4 Master（32位地址，32位数据）
    ▼
┌─────────────────────────────────────────┐
│              ysyxSoCFull                │
│                                         │
│   xbar (AXI4Xbar) ← 地址译码，自动路由   │
│     │                                   │
│     ├── AXI4 直连设备                    │
│     │    ├─ MROM  @ 0x20000000 (4KB)    │
│     │    └─ SRAM  @ 0x0f000000 (8KB)    │
│     │                                   │
│     └── AXI4 → APB 转换 → APB 设备      │
│          ├─ UART  @ 0x10000000          │
│          ├─ SPI   @ 0x10001000          │
│          ├─ GPIO  @ 0x10002000          │
│          ├─ Flash @ 0x30000000 (XIP)    │
│          ├─ VGA   @ 0x21000000          │
│          ├─ PSRAM @ 0x80000000          │
│          └─ SDRAM @ 0xa0000000          │
└─────────────────────────────────────────┘
```

## 地址路由原理

ysyxSoC 用 Rocket Chip 的 Diplomacy 框架搭建。Diplomacy 不是硬件模块，而是 Scala 元编程框架，在编译期（生成 Verilog 之前）工作：

1. 每个设备声明自己的地址范围（比如 UART 占 0x10000000 ~ 0x10000FFF）
2. Crossbar 收集所有设备的地址声明，自动生成地址译码逻辑
3. 协议不匹配时自动插入转换器（如 AXI4ToAPB）

最终生成的 `ysyxSoCFull.v` 是普通 Verilog，已包含所有路由和转换逻辑。Diplomacy 只在生成阶段起作用，运行时不存在。

## CPU 负责什么 vs SoC 负责什么

CPU 负责：
- 发出正确的 AXI4 读写请求（地址、数据、size、burst 等信号）
- 遵守 AXI4 握手协议（valid/ready）
- 处理中断输入（`io_interrupt`）

SoC 负责：
- 根据地址自动路由到对应设备
- AXI4 到 APB 的协议转换
- burst 分片（AXI4Fragmenter，把多拍 burst 拆成单拍）
- 时序隔离（Delayer）

## ysyx_23060000 内部架构

```
IFU ──AXI4(id=0)──┐
                   ├── 地址判断
LSU ──AXI4(id=1)──┘
      │            │
  0x0200_xxxx    其他地址
      │            │
    CLINT     IFU/LSU仲裁 → AXI4 Master → 送给 SoC
                          ← R通道按id路由（0→IFU, 1→LSU）
```

IFU 内部有 inst_queue（深度4）缓冲 R 响应，r.ready 不依赖下游流水线，避免与 LSU 形成死锁。

CLINT 放在 CPU 内部是因为它是 CPU 私有的定时器，不需要走外部总线。其他所有地址都通过一个 AXI4 Master 端口送出去，SoC 的 crossbar 负责分发。

## Store 到 UART 的完整链路示例

执行 `sb a4, 0(a5)` 其中 a5=0x10000000 时：

1. LSU 在 AW 通道发出 awaddr=0x10000000, awvalid=1
2. LSU 在 W 通道发出 wdata='A', wstrb=0001, wvalid=1
3. 仲裁器把请求送到 AXI4 Master 端口
4. SoC 的 xbar 看到地址 0x10000000，路由到 APB 转换器
5. APB 转换器把 AXI4 写转成 APB 写，送到 UART16550
6. UART 收到写入，把字符放进 TX FIFO
7. SoC 在 B 通道返回 bvalid=1, bresp=OKAY
8. LSU 收到响应，这笔 store 完成

CPU 只需保证 AXI4 协议正确、地址正确。路由、转换、设备交互全是 SoC 的事。

## DiffTest 架构

DiffTest（差分测试）用 NEMU 作为参考模型，逐条指令对比 NPC 和 NEMU 的状态。

```
NPC (Verilator 仿真)                    NEMU (.so 共享库)
    │                                       │
    │  每条指令 commit 时                     │
    │  DPI-C → sim_set_gpr(32次)            │
    │  DPI-C → sim_difftest(pc,csr)         │
    │                                       │
    ▼                                       ▼
testbench (C++)                         dlopen 加载
    │                                       │
    │  npc_cpu = {gpr[], pc, csr}           │  ref_difftest_exec(1)
    │                                       │  ref_difftest_regcpy → ref_cpu
    │                                       │
    └──────── 比较 npc_cpu vs ref_cpu ───────┘
```

关键约定：
- NEMU 必须编译为 .so（CONFIG_TARGET_SHARE），不能是 PIE
- 初始化时需要 memcpy 镜像到 NEMU 的对应地址（MROM 0x20000000）
- 初始化时需要 regcpy 同步初始寄存器状态（特别是 mstatus=0x1800）
- NPC 的 CPU_state 结构体必须与 NEMU 的字段布局完全一致
- regcpy 只能操作 NEMU 侧状态，无法反向写入 RTL 寄存器

## AXI4 resp 与 Access Fault

AXI4 协议中 R 通道和 B 通道都有 resp 字段（2位）：
- 0b00 (OKAY) — 正常完成
- 0b01 (EXOKAY) — 独占访问成功
- 0b10 (SLVERR) — 从设备错误
- 0b11 (DECERR) — 地址译码错误（没有设备响应该地址）

ysyxSoC 的 xbar 在地址无法匹配任何设备时返回 DECERR。CPU 应检查 resp 并触发对应异常：
- 取指 resp 非零 → Instruction Access Fault (mcause=1)
- Load resp 非零 → Load Access Fault (mcause=5)
- Store resp 非零 → Store/AMO Access Fault (mcause=7)

这比让总线挂死要好——至少能跳到 trap handler 报告错误地址。

## Abstract Machine 目录结构与命名规则

AM 的架构是一个 **ISA × 平台** 的二维矩阵。

### scripts/ — 编译配置

- `scripts/isa/{isa}.mk` — 纯 ISA 相关（工具链前缀、objdump），与平台无关
- `scripts/platform/{platform}.mk` — 纯平台相关（AM_SRCS、链接脚本、image/run 目标），与 ISA 无关
- `scripts/{isa}-{platform}.mk` — 入口文件，组合两个维度 + 扩展（如 im 加 libgcc）

例：`riscv32im-ysyxsoc.mk` 包含 `isa/riscv.mk` + `platform/ysyxsoc.mk`，再加 RV32IM 特有的 march/libgcc。

### am/src/ — 源码实现

- `am/src/{isa}/{platform}/` — ISA+平台绑定的代码（start.S、trm.c、linker.ld）
  - 不同平台地址映射不同，所以各自独立：npc 的 putch 写 0xa00003f8，ysyxsoc 写 0x10000000
- `am/src/platform/{name}/` — 跨 ISA 的通用代码
  - `dummy/` — 空桩实现（vme.c、mpe.c），任何不支持虚存/多处理器的平台都复用
  - `nemu/` — NEMU 模拟器统一定义外设地址（0xa0000xxx），所以 IOE 代码可跨架构共享

## 数据前递与记分牌

forward_sources 本质上是一张分布式的记分牌（Scoreboard）：
- Valid=1 的源：数据已就绪，可以直接旁路
- Valid=0 的源：占坑但数据未就绪，IDU 必须暂停等待

通用心法——只要一条指令进入了部件还没退休，它就必须在 ForwardingBus 上把 pend 拉高并亮出 uop_id 和 rd。数据算好了拉高 valid 放上数据，没算好就 valid=0。IDU 找 ID 距离最近的源，数据没好就 Stall。

多周期单元（乘除法器）也遵循同样原则：内部忙时暴露 is_busy/busy_rd/busy_uop_id 作为等待源。

## RISC-V 测试程序分类

- `rv32ui/um/ua/uc/uf/ud`：指令集扩展的模块化测试（u 前缀表示非特权指令，任何模式下行为一致）
- `rv32mi`：M 模式特有行为（CSR、异常处理、mret/wfi）
- `rv32si`：S 模式特有行为（虚存、页表、sfence.vma）
- U 模式验证藏在 mi 的权限违规测试中，不需要独立测试目录

开发顺序：先跑通 rv32ui → rv32mi → 再考虑 U/S 模式支持。

## 踩坑记录

- char-test.c 不加 -O2 时 gcc 生成函数序言，sp 未初始化导致 store 到非法地址，AXI 总线挂死
- verilator 需要 --autoflush 才能让 $write 无换行时也刷新 stdout
- **IFU/LSU 共享 AXI4 端口死锁**：IFU 和 LSU 共享同一个 AXI4 master（同 ID=0），当 LSU 发起 load 时，IFU 的预取 R 响应可能排在前面，但 IFU 的 `r.ready` 依赖下游 `io.out.ready`，而流水线被 LSU 阻塞 → 死锁环路。解决方案：
  1. IFU 加 `inst_queue`（Queue, 深度4）缓冲 R 通道响应，`r.ready` 只看 queue 是否满，不依赖下游
  2. IFU 用 id=0，LSU 用 id=1，顶层根据 `r.bits.id` 路由 R 响应
- NEMU 编译 .so：必须 menuconfig 选 TARGET_SHARE，且用 `tools/kconfig/build/conf --syncconfig Kconfig` 更新 autoconf.h（仅改 .config 不够）
- NEMU filelist.mk 中 CONFIG_TARGET_NATIVE_ELF 会加 -pie 覆盖 -shared，导致产物是 PIE 而非 .so
- NPC CSR mstatus 初始值必须为 0x1800（MPP=Machine），与 NEMU 一致
- NPC debug_csr 顺序 [0]=mcause [1]=mepc [2]=mstatus [3]=mtvec；NEMU CSR struct { mtvec, mepc, mstatus, mcause }
