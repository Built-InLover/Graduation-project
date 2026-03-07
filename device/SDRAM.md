# SDRAM — ysyxSoC 片外 SDRAM 子系统

RTL 源码：`ysyxSoC/perip/sdram/`
颗粒型号：MT48LC16M16A2（256Mbit, 16M × 16-bit）

---

## 1. 整体架构

```
CPU ──AXI4──► xbar ──► Fragmenter ──► xbar2 ──► AXI4ToAPB ──► APBDelayer ──► APBFanout
                                                                                │
                                         ┌──────────────────────────────────────┘
                                         ▼
                                  APBSDRAM (Chisel LazyModule)       [APB 模式，当前使用]
                                         │
                                         ▼
                                 sdram_top_apb.v (APB 接口 + 信号锁存)
                                         │
                                         ▼
                               sdram_axi_core.v (SDRAM 控制器，Ultra-Embedded)
                                         │
                              ┌──────────┴──────────┐
                         SDRAM 物理信号              DPI-C 接口
                    (clk/cke/cs/ras/cas/we/          (sdram_read /
                     a[12:0]/ba[1:0]/dqm[1:0]/        sdram_write)
                     dq[15:0])                              │
                              │                             │
                              ▼                             ▼
                           sdram.v (颗粒仿真模型)    test_bench_soc.cpp
                          MT48LC16M16A2              unordered_map<uint32_t, uint16_t>
```

也可配置为 AXI4 直连模式（`Config.sdramUseAXI = true`），此时走 `sdram_top_axi.v`。当前 ysyxSoC 使用 APB 模式。

---

## 2. 地址路由：SoC.scala

```scala
// src/SoC.scala:46-54
val sdramAddressSet = AddressSet.misaligned(0xa0000000L, 0x2000000)  // 32MB
val lsdram_apb = if (!Config.sdramUseAXI) Some(LazyModule(new APBSDRAM(sdramAddressSet))) else None
val lsdram_axi = if ( Config.sdramUseAXI) Some(LazyModule(new AXI4SDRAM(sdramAddressSet))) else None

// APB 模式：挂到 apbxbar
lsdram_apb.get.node := apbxbar

// AXI4 模式：直连 xbar（绕过 APB 链路）
lsdram_axi.get.node := ysyx.AXI4Delayer() := xbar
```

SDRAM 物理信号在顶层连接（`src/SoC.scala:145-146`）：

```scala
val sdram = Module(new sdram)   // 实例化颗粒仿真模型
sdram.io <> masic.sdram         // 连接到 SoC 顶层 SDRAM bundle
```

---

## 3. SDRAM.scala — Chisel Diplomacy 封装

```
文件: src/device/SDRAM.scala
```

定义了两个 LazyModule 包装器，将 Verilog BlackBox 接入 Diplomacy 节点：

```scala
class SDRAMIO extends Bundle {
  val clk = Output(Bool())
  val cke = Output(Bool())
  val cs  = Output(Bool())
  val ras = Output(Bool())
  val cas = Output(Bool())
  val we  = Output(Bool())
  val a   = Output(UInt(13.W))
  val ba  = Output(UInt(2.W))
  val dqm = Output(UInt(2.W))
  val dq  = Analog(16.W)    // 双向信号
}

class APBSDRAM(address: Seq[AddressSet]) extends LazyModule {
  val node = APBSlaveNode(...)  // APB slave，executable=true
  class Impl extends LazyModuleImp(this) {
    val msdram = Module(new sdram_top_apb)
    // APB in → sdram_top_apb → sdram_bundle（物理引脚）
  }
}
```

`executable = true` 表示允许从该地址空间取指，与 PSRAM 相同。

---

## 4. sdram_top_apb.v — APB 包装层

```
文件: perip/sdram/sdram_top_apb.v
```

### 4.1 APB 信号锁存

APB setup phase（`psel=1, penable=0`）时锁存所有输入，确保整个事务期间信号稳定：

```verilog
always @(posedge clock) begin
    if (in_psel && !in_penable) begin  // setup phase
        addr_latch  <= in_paddr;
        wdata_latch <= in_pwdata;
        strb_latch  <= in_pstrb;
        write_latch <= in_pwrite;
    end
end
```

### 4.2 三态状态机

```
ST_IDLE ──(is_read || is_write)──► ST_WAIT_ACCEPT ──(req_accept)──► ST_WAIT_ACK
   ▲                                                                       │
   └─────────────────────────────(in_pready)─────────────────────────────┘
```

| 状态 | 含义 |
|------|------|
| `ST_IDLE` | 等待新的 APB 事务 |
| `ST_WAIT_ACCEPT` | 等待控制器接受请求（`req_accept=1`，即控制器进入 STATE_READ/STATE_WRITE0） |
| `ST_WAIT_ACK` | 等待控制器应答（`in_pready=1`，即控制器 ack_q 拉高） |

`is_read`/`is_write` 组合逻辑：setup phase 直接用 `in_psel && !in_penable && in_pwrite`；`ST_WAIT_ACCEPT` 期间用 `write_latch`。

### 4.3 控制器参数

```verilog
sdram_axi_core #(
    .SDRAM_MHZ(100),
    .SDRAM_ADDR_W(24),   // {row[12:0], bank[1:0], col[8:0]}
    .SDRAM_COL_W(9),
    .SDRAM_READ_LATENCY(3)  // 关键参数，见 §5.3
) u_sdram_ctrl(...)
```

---

## 5. sdram_axi_core.v — SDRAM 控制器

```
文件: perip/sdram/core_sdram_axi4/sdram_axi_core.v
来源: Ultra-Embedded (https://github.com/ultraembedded/core_sdram_axi4)，GPL 许可
```

### 5.1 状态机

```
STATE_INIT ──(初始化完成)──► STATE_IDLE
                                  │
              ┌───────────────────┼───────────────────┐
              │                   │                   │
           无开行              行命中              行未命中/行冲突
              │                   │                   │
        STATE_ACTIVATE        STATE_WRITE0       STATE_PRECHARGE
              │              or STATE_READ               │
         (tRCD 延迟)               │               STATE_ACTIVATE
              │                   │                     │
        STATE_WRITE0          STATE_WRITE1         (tRCD 延迟)
        或 STATE_READ              │
              │               STATE_IDLE
        STATE_WRITE1
        或 STATE_READ_WAIT
              │             │
         STATE_IDLE    (tCL 延迟)
                             │
                        STATE_DELAY
                             │
                        STATE_IDLE
```

关键状态说明：

| 状态 | 发出命令 | 说明 |
|------|----------|------|
| `STATE_ACTIVATE` | CMD_ACTIVE | 选中 row，tRCD 后转入 READ 或 WRITE0 |
| `STATE_WRITE0` | CMD_WRITE + data[15:0] | 发写命令和低 16 位，`ram_accept_w=1` |
| `STATE_WRITE1` | CMD_NOP + data[31:16] | 发高 16 位，`ack_q=1` |
| `STATE_READ` | CMD_READ | 发读命令，`ram_accept_w=1` |
| `STATE_READ_WAIT` | CMD_NOP | 等待 CAS latency |
| `STATE_DELAY` | CMD_NOP | 通用延迟状态，由 `delay_r` 计数控制 |

**时序参数**（100MHz 下）：

```
SDRAM_TRCD_CYCLES = ceil(20ns / 10ns) = 2   (ACTIVATE → READ/WRITE)
SDRAM_TRP_CYCLES  = ceil(20ns / 10ns) = 2   (PRECHARGE → ACTIVATE)
SDRAM_TRFC_CYCLES = ceil(60ns / 10ns) = 6   (REFRESH 周期)
```

### 5.2 地址拆分

CPU 地址 `inport_addr_i[31:0]`（相对偏移量，控制器自身不限制基地址）：

```verilog
// SDRAM_COL_W=9, SDRAM_ADDR_W=24
wire addr_col_w  = {{(13-9){1'b0}}, ram_addr_w[9:2], 1'b0};  // [9:2] 为列地址，bit0=0（burst起始）
wire addr_row_w  = ram_addr_w[24:12];  // [24:12] 为行地址（13位）
wire addr_bank_w = ram_addr_w[11:10];  // [11:10] 为 bank 地址（2位）
```

32-bit CPU 地址 → 24-bit SDRAM 物理地址：`{row[12:0], bank[1:0], col[8:0]}`

### 5.3 读数据流水线与 SDRAM_READ_LATENCY

控制器使用反相时钟驱动 SDRAM（`sdram_clk_o = ~clk_i`），并有 2 级采样流水线：

```
控制器发出 CMD_READ（system posedge N）
    │
    ▼（chip posedge = system negedge N+0.5）
芯片接收 READ 命令，CAS=2 开始计数
    │
    ▼（经过 CAS 延迟后，chip posedge N+2.5）
芯片组合逻辑输出 dq（第一个 16-bit beat）
    │
    ▼（system posedge N+3）
sample_data0_q ← dq
    │
    ▼（system posedge N+4）
sample_data_q ← sample_data0_q     ←── 这就是第一个 beat 进入 {sample_data_q}

rd_q 移位寄存器（SDRAM_READ_LATENCY=3，宽度=5）：
  STATE_READ 时（posedge N）: rd_q <= {rd_q[3:0], 1}  → rd_q = xxxxx1
  STATE_READ_WAIT 后进 DELAY（3 个周期）后回 IDLE
  共经过 5 步：rd_q[4] 在 posedge N+4 时读到 OLD rd_q = 1xxxx0

data_buffer_q：rd_q[4]=1 时捕获 sample_data_q（第一个 beat）
ack_q：rd_q[4]=1 时拉高 → APB pready

ram_read_data_w = {sample_data_q, data_buffer_q}
                = {第二个beat(高16位), 第一个beat(低16位)}
```

`SDRAM_READ_LATENCY` 的含义：`STATE_READ_WAIT` 进入 `STATE_DELAY` 时的延迟周期数。原始值 2 不够，需改为 **3**，原因是 Verilator NBA "read before write" 语义导致 `rd_q` 检查读到旧值，实际触发比 bit 位置晚一拍。

---

## 6. sdram.v — SDRAM 颗粒仿真模型

```
文件: perip/sdram/sdram.v
```

自行实现的 MT48LC16M16A2 行为模型，替代 ysyxSoC 原有空桩。

### 6.1 颗粒规格

| 参数 | 值 |
|------|-----|
| 容量 | 256Mbit (16M × 16-bit) |
| Banks | 4 |
| Rows | 8192 (13-bit) |
| Columns | 512 (9-bit，控制器用 col[8:0]，模型用 col[9:0] 宽) |
| 数据宽度 | 16-bit |
| CAS Latency | 2（可通过 LOAD_MODE 设置） |
| Burst Length | 2（发出 2 × 16-bit = 32-bit 每次读写，可通过 LOAD_MODE 设置） |

### 6.2 命令编码

| 命令 | {cs, ras, cas, we} | 说明 |
|------|-------------------|------|
| NOP | 0111 | 无操作 |
| ACTIVE | 0011 | 激活 bank/row |
| READ | 0101 | 发起读（需在 S_ACTIVE 状态） |
| WRITE | 0100 | 发起写（需在 S_ACTIVE 状态） |
| PRECHARGE | 0010 | 关闭行（a[10]=1 关闭所有 bank） |
| REFRESH | 0001 | 自动刷新 |
| LOAD_MODE | 0000 | 写模式寄存器 |

### 6.3 状态机

```
S_IDLE ──CMD_ACTIVE──► S_ACTIVE ──CMD_READ───► S_READ ──(cas_cnt减到0)──► S_READ_DATA
                           │                                                     │
                           │ CMD_WRITE                               (burst_cnt递减)
                           ▼                                                     │
                        [写采样第一beat]                              (burst_cnt==1)
                           │                                                     ▼
                         S_WRITE                                             S_ACTIVE  ← 关键修复
                       (burst_cnt递减)
                           │
                      (burst_cnt==1)
                           ▼
                        S_ACTIVE  ← 关键修复（原为 S_IDLE）
```

**关键点**：burst 结束后回到 `S_ACTIVE`，而非 `S_IDLE`。行在 PRECHARGE 前始终保持打开。

### 6.4 读数据输出

为兼容 Verilator 零延迟仿真，读数据用**组合逻辑**驱动（非寄存器）：

```verilog
// 读数据准备（组合逻辑计算下一个地址的数据）
always @(*) begin
    if (state == S_READ_DATA && cke)
        sdram_read({7'b0, active_row[current_bank], current_bank, col_addr}, dq_out_next);
    else
        dq_out_next = 16'b0;
end

// 读数据输出（组合逻辑驱动总线）
always @(*) begin
    if (state == S_READ_DATA && cke) begin
        dq_oe = 1'b1;
        dq_out = dq_out_next;
    end else begin
        dq_oe = 1'b0;
        dq_out = 16'b0;
    end
end
```

### 6.5 写数据采样

写数据在时钟上升沿采样，`write_sample` 信号捕获 WRITE 命令当拍（第一个 beat）以及后续 S_WRITE 状态（后续 beats）：

```verilog
// 写采样条件：WRITE 命令当拍 OR S_WRITE 续传
wire write_sample = cke && (
    (state == S_ACTIVE && cmd == CMD_WRITE) ||  // 第一个 beat
    (state == S_WRITE)                           // 后续 beats
);

// 写地址选择：WRITE 命令时用 a[9:0]，后续用已递增的 col_addr
wire [9:0] write_col  = (state == S_ACTIVE) ? a[9:0] : col_addr;
wire [1:0] write_bank = (state == S_ACTIVE) ? ba : current_bank;

always @(posedge clk) begin
    if (write_sample)
        sdram_write({7'b0, active_row[write_bank], write_bank, write_col}, dq, {6'b0, dqm});
end
```

WRITE 命令收到后，状态机：
- `col_addr <= a[9:0] + 1`（第一个 beat 已消费，地址提前+1）
- `burst_cnt <= burst_length - 1`（第一个 beat 已消费）

### 6.6 DPI-C 接口（稀疏存储）

```verilog
import "DPI-C" function void sdram_read(
    input int unsigned addr,
    output shortint unsigned data
);
import "DPI-C" function void sdram_write(
    input int unsigned addr,
    input shortint unsigned data,
    input byte unsigned dqm
);
```

地址格式（25-bit）：`{7'b0, row[12:0], bank[1:0], col[9:0]}`

对应 C++ 实现（`test_bench_soc.cpp`）：

```cpp
static std::unordered_map<uint32_t, uint16_t> sdram_storage;

extern "C" void sdram_read(uint32_t addr, uint16_t* data) {
    auto it = sdram_storage.find(addr);
    *data = (it != sdram_storage.end()) ? it->second : 0;
}

extern "C" void sdram_write(uint32_t addr, uint16_t data, uint8_t dqm) {
    // dqm[1]=高字节屏蔽, dqm[0]=低字节屏蔽（0=写入，1=屏蔽）
    uint16_t old_data = sdram_storage.count(addr) ? sdram_storage[addr] : 0;
    uint16_t new_data = old_data;
    if (!(dqm & 0x1)) new_data = (new_data & 0xff00) | (data & 0x00ff);
    if (!(dqm & 0x2)) new_data = (new_data & 0x00ff) | (data & 0xff00);
    sdram_storage[addr] = new_data;
}
```

---

## 7. 时序细节：Verilator 下的时钟域

控制器使用反相时钟：`sdram_clk_o = ~clk_i`。Verilator 全局 NBA 语义下，系统时钟域（posedge clk_i）和芯片时钟域（posedge sdram_clk = negedge clk_i）时序关系：

```
system clk:   ___/‾‾‾\___/‾‾‾\___/‾‾‾\___
sdram  clk:   ‾‾‾\___/‾‾‾\___/‾‾‾\___/‾‾‾

system posedge N:  控制器更新命令/数据（NBA 在本 posedge 生效）
sdram  posedge N+0.5:  芯片采样命令（negedge system = posedge chip）
→ 芯片在系统 posedge N 后的 negedge 才能看到 N 时刻发出的命令
→ 相当于命令有 1 个芯片时钟（= 半个系统时钟）的延迟
```

读路径延迟分析（以 CAS=2 为例）：

```
系统 posedge N:   STATE_READ，发 CMD_READ
芯片 posedge N+0.5: 收到 READ，cas_cnt=2
芯片 posedge N+1.5: cas_cnt=1
芯片 posedge N+2.5: cas_cnt=0 → state=S_READ_DATA，组合逻辑驱动 dq（第一 beat）
系统 posedge N+3:  sample_data0_q ← 第一 beat
系统 posedge N+4:  sample_data_q ← 第一 beat（两级流水稳定）

rd_q 移位（STATE_READ 时插入 1）经过 SDRAM_READ_LATENCY+1=4 步后 rd_q[4]=1
→ 在 posedge N+4 时 rd_q[4] 读 OLD 值（上一拍的 rd_q = 10000），触发 data_buffer_q 捕获
→ data_buffer_q ← sample_data_q（= 第一 beat）
→ ack_q ← 1（APB pready 在下一周期生效）
```

因此 `SDRAM_READ_LATENCY=3` 是 Verilator 下的正确值。

---

## 8. 踩坑经验

### 坑 1：写时序 — 芯片比控制器晚一拍采样第一个 beat

**现象**：写入后读回，每个 32-bit word 的低 16-bit 为 0，高 16-bit 正确。

**根本原因**：芯片 S_ACTIVE 收到 CMD_WRITE 时，在 S_ACTIVE 的 Verilog `case` 分支里用 NBA 跳转到 S_WRITE，但写采样条件是 `state == S_WRITE`。由于 NBA 在当前 posedge 结束后才生效，当拍 state 仍为 S_ACTIVE，采样条件不满足。第一个 beat 的数据（低 16-bit）被丢弃，只有第二个 beat（高 16-bit）在 S_WRITE 状态采样成功。

**修复**：添加 `write_sample` wire，同时覆盖 `(state == S_ACTIVE && cmd == CMD_WRITE)` 和 `(state == S_WRITE)` 两种情况。

### 坑 2：读时序 — 芯片 negedge 驱动 dq，控制器 posedge 采样，Verilator NBA 竞争

**现象**：读回数据全为 0x00000000。

**根本原因**：芯片原始实现在 `always @(negedge clk)` 用 NBA 驱动 dq。Verilator 中芯片的 negedge = 系统 posedge，而控制器也在系统 posedge 采样 `sdram_data_input_i`。NBA 语义下"读发生在写之前"，控制器采样到 dq 的旧值（全 0）。

**修复**：将芯片 dq 输出改为组合逻辑（`always @(*)`），NBA 应用后 Verilator 会重新评估组合逻辑，控制器在同一 posedge 的采样能看到最新值。

### 坑 3：读延迟校准 — SDRAM_READ_LATENCY=2 给出 0x11110000，=4 给出 0x0000AABB

**现象**：改组合逻辑后读到半对的数据，调整 `SDRAM_READ_LATENCY` 数值后结果在两种错误之间跳。

**根本原因**：`rd_q[SDRAM_READ_LATENCY+1]` 的触发时机受 Verilator NBA 影响。该 bit 是移位寄存器，检查的是 **OLD** rd_q（posedge 采样前的值），导致实际触发比字面 bit 位置晚一拍。LATENCY=2 时 `data_buffer_q` 捕获时机偏早（捕到 0），LATENCY=4 时偏晚（捕到第二个 beat 作低位）。

**修复**：经时序推导，`SDRAM_READ_LATENCY=3` 恰好使 `data_buffer_q` 在第一个 beat 稳定后捕获，`sample_data_q` 在第二个 beat 稳定后输出，组成正确的 `{高16, 低16}`。

### 坑 4：背靠背写读失败 — burst 结束后芯片跑到 S_IDLE，控制器 row-hit 跳过 ACTIVATE

**现象**：`sdram-addr-test`（写和读之间有 `putch()` 延迟）通过，`sdram-mem-test`（紧接着写立即读）第一个 word 就读回 0x00000000。

**根本原因**：控制器维护 `row_open_q` 跟踪行状态，连续访问同一行时会跳过 ACTIVATE 直接发 READ 命令（行命中优化）。但芯片模型原始实现在写 burst 结束后跳回 `S_IDLE`，`S_IDLE` 不响应 `CMD_READ`，命令被忽略，芯片无输出。

`sdram-addr-test` 通过的原因：`putch()` 耗费大量周期，期间 SDRAM 刷新定时器（`SDRAM_REFRESH_CYCLES`）到期，控制器执行 PRECHARGE + REFRESH，`row_open_q` 清零。下次读时走完整的 ACTIVATE → READ 路径，芯片从 `S_IDLE` 正常处理。

**修复**：一行改动：

```verilog
// 修复前
S_WRITE: if (burst_cnt <= 1) state <= S_IDLE;

// 修复后
S_WRITE: if (burst_cnt <= 1) state <= S_ACTIVE;  // 行仍打开

// S_READ_DATA 同理
S_READ_DATA: if (burst_cnt <= 1) state <= S_ACTIVE;
```

这符合真实 SDRAM 行为：行在 PRECHARGE 命令到来之前一直处于激活状态，芯片应接受 READ/WRITE/PRECHARGE 命令。

---

## 9. 完整地址映射

| CPU 地址 | 大小 | 含义 |
|----------|------|------|
| 0xa0000000 | 32MB | SDRAM（MT48LC16M16A2 实际 32MB，但颗粒只有 32MB × 8-bit = 32MB，此处用 32MB 窗口） |

实际颗粒容量：4 banks × 8192 rows × 512 cols × 16-bit = 32MB。

---

## 10. 测试验证

| 测试 | 内容 | 结果 |
|------|------|------|
| `sdram-addr-test` | 单 word（0xAABBCCDD）写读校验 | PASS |
| `sdram-simple` | 基础读写测试 | PASS |
| `sdram-test` | 多 word 测试 | PASS |
| `sdram-mem-test` | 256B 区域，8/16/32-bit 全模式，包含 batch 写读和即写即读 | PASS（输出 `WR1234P`） |

`sdram-mem-test` 验证路径：
- `W` — 32-bit 批量写 64 个 word
- `R` — 批量读回校验（`1`）
- 逐个写立即读校验（`2`）
- 16-bit 写读（`3`）
- 8-bit 写读（`4`）
- 全部通过（`P`）

---

## 11. 核心概念梳理

### 11.1 SDR SDRAM 的两个关键时序参数

- **tRCD（Row-to-Column Delay）**：ACTIVE 命令到 READ/WRITE 命令之间的最小间隔。物理含义：行地址选通完成，列放大器稳定所需时间。
- **CAS Latency（CL）**：READ 命令发出到第一个有效数据出现之间的时钟周期数。物理含义：DRAM 内部列地址译码和放大器响应时间。

### 11.2 Burst 的含义

一次 READ/WRITE 命令连续读写多个连续列地址的数据。BL=2 意味着每次命令读写 2 个 16-bit = 32-bit，恰好等于 CPU 数据总线宽度。控制器通过两拍数据传输拼出一个完整的 32-bit 读写事务。

### 11.3 行命中优化（Row Hit）

控制器记录每个 bank 的当前激活行（`row_open_q` + `active_row_q`）。若下次访问的 {bank, row} 与上次相同，则跳过 PRECHARGE + ACTIVATE，直接发 READ/WRITE，节省约 4 个系统时钟周期（2×tRCD + 2×tRP）。

这个优化要求芯片在 burst 结束后也维持 S_ACTIVE 状态（行仍然打开），这正是坑 4 修复的关键。

### 11.4 DQM（数据掩码）

`dqm[1:0]` 控制写入的字节粒度：
- `dqm[0]=1`：屏蔽低字节（data[7:0] 不写入）
- `dqm[1]=1`：屏蔽高字节（data[15:8] 不写入）

控制器根据 APB `strb[3:0]` 转换：
- byte 写（`strb=0001`）：`dqm = ~strb[1:0]` 只写低字节
- halfword 写（`strb=0011`）：`dqm = 0b00`，写全部
- 32-bit 写分两个 16-bit burst，`dqm_buffer_q` 缓存第二个 beat 的掩码
