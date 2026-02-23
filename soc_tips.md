# ysyxSoC 系统知识笔记

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
- `am/src/native/` — 宿主机原生运行，每个人环境不同，单独实现

### 为什么 mycore 没有独立源码目录

mycore 和 npc 的硬件地址映射完全一样，只是支持的指令集不同。所以 `platform/mycore.mk` 直接复用 `riscv/npc/` 的源文件，不需要独立目录。

### 真实硬件 vs 模拟器

NEMU 作为模拟器，自己统一定义了所有 ISA 的外设地址映射（都是 0xa0000xxx），所以 IOE 代码可以放在 `am/src/platform/nemu/` 跨架构共享。而真实硬件平台（npc、ysyxsoc）的地址映射各不相同，只能放在 `am/src/riscv/{platform}/` 下。

## 踩坑记录

- char-test.c 不加 -O2 时 gcc 生成函数序言，sp 未初始化导致 store 到非法地址，AXI 总线挂死
- verilator 需要 --autoflush 才能让 $write 无换行时也刷新 stdout
- **IFU/LSU 共享 AXI4 端口死锁**：IFU 和 LSU 共享同一个 AXI4 master（同 ID=0），当 LSU 发起 load 时，IFU 的预取 R 响应可能排在前面，但 IFU 的 `r.ready` 依赖下游 `io.out.ready`，而流水线被 LSU 阻塞 → 死锁环路。解决方案：
  1. IFU 加 `inst_queue`（Queue, 深度4）缓冲 R 通道响应，`r.ready` 只看 queue 是否满，不依赖下游
  2. IFU 用 id=0，LSU 用 id=1，顶层根据 `r.bits.id` 路由 R 响应（替代 r_source_queue FIFO 跟踪方案）
