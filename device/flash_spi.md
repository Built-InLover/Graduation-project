# Flash & SPI 子系统详解

## 1. 整体架构

```
CPU ──AXI4──► xbar ──► Fragmenter ──► xbar2 ──► AXI4ToAPB ──► APBDelayer ──► APBFanout
                                                                                 │
                                          ┌──────────────────────────────────────┘
                                          ▼
                                      APBSPI (Chisel LazyModule)
                                          │
                                          ▼
                                    spi_top_apb.v (APB 接口)
                                          │
                              ┌───────────┴───────────┐
                              ▼                       ▼
                     SPI 寄存器访问            XIP Flash 读取
                    (0x10001000)             (0x30000000)
                     APB 直连 WB              状态机驱动 WB
                              │                       │
                              └───────────┬───────────┘
                                          ▼
                                   spi_top.v (Wishbone SPI Master)
                                     │          │
                                  spi_clgen  spi_shift
                                     │          │
                                     └────┬─────┘
                                          ▼
                                    SPI 总线 (sck/mosi/miso/ss)
                                          │
                              ┌───────────┴───────────┐
                              ▼                       ▼
                          flash.v                 bitrev.v
                        (SS[0], 仿真)           (SS[7], 测试)
```

## 2. 地址路由：SoC.scala

两段地址注册到同一个 APBSPI 节点，APBFanout 自动生成选择逻辑：

```scala
// src/SoC.scala:38-41
val lspi = LazyModule(new APBSPI(
  AddressSet.misaligned(0x10001000, 0x1000) ++    // SPI 控制器寄存器 (4KB)
  AddressSet.misaligned(0x30000000, 0x10000000)   // XIP Flash (256MB)
))
```

路由拓扑（src/SoC.scala:50-56）：
```scala
List(lspi.node, luart.node, ...).map(_ := apbxbar)           // APB 外设挂到 fanout
List(apbxbar := APBDelayer() := AXI4ToAPB() := AXI4Buffer(), // APB 链路
     lmrom.node, sramNode).map(_ := xbar2)                   // AXI4 直连设备
xbar2 := AXI4UserYanker(Some(1)) := AXI4Fragmenter() := xbar
xbar := cpu.masterNode
```

Flash 和 bitrev 在顶层连接（src/SoC.scala:135-141）：
```scala
val flash = Module(new flash)
flash.io <> masic.spi
flash.io.ss := masic.spi.ss(0)        // Flash 用 SS[0]
val bitrev = Module(new bitrev)
bitrev.io <> masic.spi
bitrev.io.ss := masic.spi.ss(7)       // bitrev 用 SS[7]
masic.spi.miso := List(bitrev.io, flash.io).map(_.miso).reduce(_ && _)  // MISO 线与
```

## 3. SPI.scala — APBSPI LazyModule

```
文件: src/device/SPI.scala
```

Diplomacy 封装层，将 APB 端口连接到 spi_top_apb BlackBox：

```scala
class APBSPI(address: Seq[AddressSet]) extends LazyModule {
  val node = APBSlaveNode(...)  // 声明 APB slave，地址范围由参数传入
  class Impl extends LazyModuleImp(this) {
    val mspi = Module(new spi_top_apb)
    mspi.io.in <> in              // APB 信号直连
    spi_bundle <> mspi.io.spi     // SPI 物理信号暴露到顶层
  }
}
```

关键点：`executable = true` 允许从该地址空间取指（XIP 必需）。

## 4. spi_top_apb.v — APB 包装 + XIP 状态机

```
文件: perip/spi/rtl/spi_top_apb.v
```

### 4.1 两种模式

| 宏 | 行为 |
|----|------|
| `FAST_FLASH` 启用 | 绕过 SPI 时序，DPI-C 直读 flash（仿真加速） |
| `FAST_FLASH` 关闭（当前） | 真实 SPI master + XIP 状态机 |

### 4.2 地址判断

```verilog
wire is_flash = (in_paddr >= 32'h30000000) && (in_paddr <= 32'h3fffffff);
```

- `is_flash = 0`：SPI 寄存器访问（0x10001000），APB 直连 spi_top Wishbone
- `is_flash = 1`：Flash XIP 读取（0x30000000），状态机接管 Wishbone

### 4.3 Wishbone MUX

```verilog
wire xip_active = (xip_state != S_IDLE);

// Wishbone 输入：IDLE 时 APB 直连，XIP 时状态机驱动
assign wb_adr   = xip_active ? xip_wb_adr : in_paddr[4:0];
assign wb_dat_i = xip_active ? xip_wb_dat : in_pwdata;
assign wb_we    = xip_active ? xip_wb_we  : in_pwrite;
assign wb_stb   = xip_active ? xip_wb_stb : in_psel;
assign wb_cyc   = xip_active ? xip_wb_cyc : in_penable;

// APB 输出：IDLE 时来自 spi_top，XIP 时来自状态机
assign in_pready  = xip_active ? (xip_state == S_DONE) : wb_ack;
assign in_prdata  = xip_active ? xip_rdata : wb_dat_o;
assign in_pslverr = xip_active ? 1'b0 : wb_err;
```

### 4.4 XIP 状态机（8 states）

```
IDLE ──flash读──► WR_TX1 ──ack──► WR_TX0 ──ack──► WR_SS ──ack──► WR_CTRL
  ▲                                                                   │
  │                                                                  ack
  │                                                                   ▼
DONE ◄──ack── RD_RX0 ◄──GO=0── POLL ◄────────────────────────────────┘
                                  │                                   ▲
                                 GO=1 ─────────────────────────────────┘
```

| 状态 | Wishbone 操作 | 说明 |
|------|--------------|------|
| IDLE | — | 等待 flash 读请求 |
| WR_TX1 | adr=0x04, dat={0x03, addr[23:2], 2'b00}, we=1 | 写 TX1：读命令 + 地址 |
| WR_TX0 | adr=0x00, dat=0, we=1 | 写 TX0：dummy（接收用） |
| WR_SS | adr=0x18, dat=1, we=1 | 选中 SS[0]（Flash） |
| WR_CTRL | adr=0x10, dat=0x2540, we=1 | ASS\|TX_NEG\|GO\|64 bits |
| POLL | adr=0x10, we=0 | 轮询 CTRL[8]（GO 位） |
| RD_RX0 | adr=0x00, we=0 | 读 RX0，bswap32 还原字节序 |
| DONE | — | pready=1，返回数据，回 IDLE |

CTRL 寄存器值 `0x2540` 的含义：
```
bit 13: ASS    = 1  (自动片选)
bit 10: TX_NEG = 1  (下降沿发送)
bit  8: GO     = 1  (启动传输)
bit 6:0: LEN   = 64 (0x40, 传输 64 bits)
```

### 4.5 写保护

```verilog
if (is_flash && in_psel && in_pwrite) $fatal;
```

### 4.6 Wishbone ACK 时序

spi_top 内部：`wb_ack_o <= wb_cyc_i & wb_stb_i & ~wb_ack_o`
- 请求发出后 1 拍 ack=1
- ack=1 后自动归零（因为 `~wb_ack_o`）
- 状态机在 ack=1 时转移并驱动新信号，下一拍 ack=0，再下一拍新 ack=1

## 5. spi_top.v — Wishbone SPI Master

```
文件: perip/spi/rtl/spi_top.v
来源: OpenCores SPI IP core (http://www.opencores.org/projects/spi/)
```

### 5.1 寄存器映射

Wishbone 地址 `wb_adr_i[4:0]`，通过 `OFS_BITS = [4:2]` 解码：

| 偏移 | OFS | 寄存器 | 读 | 写 |
|------|-----|--------|----|----|
| 0x00 | 0 | RX0 / TX0 | 接收数据 [31:0] | 发送数据 [31:0] |
| 0x04 | 1 | RX1 / TX1 | 接收数据 [63:32] | 发送数据 [63:32] |
| 0x08 | 2 | RX2 / TX2 | 接收数据 [95:64] | 发送数据 [95:64] |
| 0x0C | 3 | RX3 / TX3 | 接收数据 [127:96] | 发送数据 [127:96] |
| 0x10 | 4 | CTRL | 控制/状态 | 控制 |
| 0x14 | 5 | DIVIDER | 分频值 | 分频值 |
| 0x18 | 6 | SS | 片选 | 片选 |

### 5.2 CTRL 寄存器位定义

```
bit 13: ASS        — 自动片选（传输期间自动拉低 SS）
bit 12: IE         — 中断使能
bit 11: LSB        — LSB first（0=MSB first）
bit 10: TX_NEGEDGE — MOSI 在 SCK 下降沿驱动
bit  9: RX_NEGEDGE — MISO 在 SCK 下降沿采样
bit  8: GO         — 启动传输（硬件完成后自动清零）
bit  7: reserved
bit 6:0: CHAR_LEN  — 传输位数（0~127，实际传输 CHAR_LEN 位）
```

### 5.3 ACK 生成

```verilog
wb_ack_o <= wb_cyc_i & wb_stb_i & ~wb_ack_o;
```

单拍应答，自动归零。

### 5.4 GO 位自动清零

```verilog
if (tip && last_bit && pos_edge) begin
  ctrl[`SPI_CTRL_GO] <= 1'b0;  // 传输完成，清 GO
end
```

`tip`（Transfer In Progress）由 spi_shift 输出，`last_bit` 表示最后一位。

### 5.5 子模块

- **spi_clgen.v** — 时钟分频器，根据 DIVIDER 寄存器生成 SCK，输出 pos_edge/neg_edge 脉冲
- **spi_shift.v** — 移位寄存器，128-bit 宽，支持 MSB/LSB first，TX/RX 双向

## 6. spi_defines.v — 编译时配置

```
文件: perip/spi/rtl/spi_defines.v
```

当前配置：

| 宏 | 值 | 说明 |
|----|-----|------|
| SPI_DIVIDER_LEN | 16 | 分频寄存器 16 位 |
| SPI_MAX_CHAR | 128 | 最大传输 128 位 |
| SPI_CHAR_LEN_BITS | 7 | CHAR_LEN 字段 7 位 |
| SPI_SS_NB | 8 | 8 路片选 |

寄存器偏移解码：`SPI_OFS_BITS = [4:2]`（即 word 对齐）。

## 7. flash.v — Flash 仿真模型（SPI Slave）

```
文件: perip/flash/flash.v
```

### 7.1 状态机

```
cmd_t ──8bit──► addr_t ──24bit──► data_t（持续输出）
                  │
              cmd!=0x03 → err_t（$fatal）
```

- 仅支持 `0x03`（Read Data）命令
- MSB first，posedge sck 采样
- SS 高电平复位

### 7.2 数据流

```
1. 接收 8-bit cmd（0x03）
2. 接收 24-bit addr（最后 1 bit 在 ren 时通过 mosi 拼入 raddr）
3. addr 完成时触发 flash_cmd（DPI-C）读取 32-bit 数据
4. data_bswap = 字节翻转（大端→小端适配）
5. data_t 状态逐 bit 移出（MSB first）
```

### 7.3 flash_cmd 模块（DPI-C）

```verilog
import "DPI-C" function void flash_read(input int addr, output int data);

module flash_cmd(clock, valid, cmd, addr, data);
  always @(posedge clock)
    if (valid && cmd == 8'h03) flash_read(addr, data);
endmodule
```

仿真时由 `test_bench_soc.cpp` 提供 `flash_read()` 实现：
- 16MB 缓冲区，默认填充已知模式 `0xdeadbeef ^ (i * 0x01010101)`
- 支持从文件加载（`FLASH=` 参数）

### 7.4 MISO 输出

```verilog
assign miso = ss ? 1'b1 :                    // SS 高：空闲输出 1
  ({(state == data_t && counter == 8'd0)
    ? data_bswap : data}[31]);                // data_t：移位输出 MSB
```

## 8. SPI 总线连接（顶层）

```scala
// src/SoC.scala:135-141 (ysyxSoCFull)
val flash = Module(new flash)
flash.io <> masic.spi              // sck, mosi 共享
flash.io.ss := masic.spi.ss(0)    // Flash 用 SS[0]

val bitrev = Module(new bitrev)
bitrev.io <> masic.spi
bitrev.io.ss := masic.spi.ss(7)   // bitrev 用 SS[7]

// MISO 线与：两个 slave 空闲时输出 1，选中的输出数据
masic.spi.miso := List(bitrev.io, flash.io).map(_.miso).reduce(_ && _)
```

## 9. 完整地址映射表

| 地址范围 | 大小 | 设备 | 总线 |
|----------|------|------|------|
| 0x0f000000 | 8KB | SRAM | AXI4 直连 |
| 0x10000000 | 4KB | UART16550 | APB |
| 0x10001000 | 4KB | SPI 控制器 | APB → spi_top_apb → WB 直连 |
| 0x10002000 | 16B | GPIO | APB |
| 0x10011000 | 8B | PS2 Keyboard | APB |
| 0x20000000 | 4KB | MROM | AXI4 直连 |
| 0x21000000 | 2MB | VGA | APB |
| 0x30000000 | 256MB | Flash XIP | APB → spi_top_apb → XIP 状态机 → WB |
| 0x80000000 | 4MB | PSRAM | APB |
| 0xa0000000 | 32MB | SDRAM | APB 或 AXI4（可配置） |

## 10. 软件视角：两种 Flash 读取方式

### 10.1 软件驱动 SPI（寄存器操作）

```c
// 访问 0x10001000 SPI 寄存器
SPI_TX1 = (0x03 << 24) | addr;   // 写 TX1
SPI_TX0 = 0;                      // 写 TX0
SPI_SS  = 0x01;                   // 选 SS[0]
SPI_CTRL = ASS | TX_NEG | GO | 64;// 启动 64-bit 传输
while (SPI_CTRL & GO);            // 轮询等待
data = bswap32(SPI_RX0);          // 读结果
```

每次读需 ~6 条 MMIO 指令 + 轮询。

### 10.2 XIP 硬件透明读取

```c
// 直接读 0x30000000 地址空间
uint32_t data = *(volatile uint32_t *)0x30000000;
```

一条 load 指令，硬件状态机自动完成 SPI 传输。IFU 取指也可以走这个路径（Execute In Place）。

## 11. 核心概念梳理

### 11.1 Wishbone 是什么，为什么需要它

Wishbone 是 spi_top.v 这个 OpenCores SPI IP 核自带的总线接口。
这个 IP 核原本就是给 Wishbone 总线系统设计的，所以它的寄存器接口天然就是 Wishbone 协议。
而 ysyxSoC 用的是 APB 总线，所以需要 spi_top_apb.v 做 APB→Wishbone 的协议转换。

Wishbone 和 SPI 是完全不同层面的东西：
- **Wishbone**：CPU 侧总线协议，用来读写 spi_top 内部的寄存器（TX、RX、CTRL、SS、DIVIDER）
- **SPI**：外设侧物理协议（sck/mosi/miso/ss 四根线），spi_top 根据寄存器内容产生时序

方向是：通过 Wishbone 寄存器告诉 spi_top 要发什么 → spi_top 产生 SPI 时序 → Flash 响应。

### 11.2 三种模式对比

**模式 1：FAST_FLASH（仿真加速）**
```
CPU → APB → DPI-C flash_read() → 直接返回数据
```
绕过 spi_top 和 SPI 时序，不走任何真实硬件。

**模式 2：软件驱动 SPI（原有，关闭 FAST_FLASH）**
```
CPU (多条 MMIO load/store 到 0x10001000)
 → APB → spi_top_apb (APB→WB 一对一直连) → spi_top (WB→SPI) → flash.v
```
CPU 执行约 6 条 MMIO 指令 + 轮询，每条都是独立的 APB 事务。
spi_top_apb 的 APB→WB 桥接逻辑是原有的，不需要自己写。

**模式 3：XIP（新增，关闭 FAST_FLASH）**
```
CPU (1 条 load 到 0x30000000)
 → APB → spi_top_apb 内 XIP 状态机 (自动发 6 次 WB 操作) → spi_top (WB→SPI) → flash.v
```
底层走的 SPI 时序和模式 2 完全一样，只是把软件轮询变成了硬件自动化。

### 11.3 自己需要写的部分

在 spi_top_apb.v 的 `else`（关闭 FAST_FLASH）分支中：

1. **地址识别**：判断 APB 地址是 SPI 寄存器（0x10001000）还是 Flash XIP（0x30000000）
2. **Wishbone MUX**：SPI 寄存器访问时 APB 直连 WB（原有逻辑）；XIP 时状态机驱动 WB
3. **XIP 状态机**：本质上是一个 Wishbone 控制器，把 APB 送来的一条 load 指令自动转换成 6 次 WB 操作序列：
   - WR_TX1 → WR_TX0 → WR_SS → WR_CTRL → POLL（轮询 GO 位）→ RD_RX0 → 返回数据

