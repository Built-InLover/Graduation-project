# UART16550 — ysyxSoC 串口控制器

RTL 源码：`ysyxSoC/perip/uart16550/rtl/`

## 整体架构

```
APB Bus
  │
  ▼
uart_top_apb.v ── APB 协议适配（2 周期握手）
  │
  ▼
uart_regs.v ── 寄存器文件（地址译码、DLAB 复用、中断仲裁）
  │
  ├──► uart_transmitter.v ── 发送状态机（并→串）
  │       └── uart_tfifo.v ── TX FIFO（16×8bit）
  │               └── raminfr.v ── 双端口 RAM
  │
  ├──► uart_receiver.v ── 接收状态机（串→并）
  │       └── uart_rfifo.v ── RX FIFO（16×11bit, 含 3 位错误标志）
  │               └── raminfr.v
  │
  └──► uart_sync_flops.v ── 异步信号同步（RX 线 → 内部时钟域）
```

## 寄存器总览

地址只有 3 位（0x0~0x7），地址 0x0 和 0x1 通过 LCR[7] (DLAB) 复用：

| 偏移 | DLAB=0 读 | DLAB=0 写 | DLAB=1 | 复位值 |
|------|-----------|-----------|--------|--------|
| 0x0 | RBR（接收数据） | THR（发送数据） | DLL（除数低字节） | 0x00 |
| 0x1 | IER | IER | DLM（除数高字节） | 0x00 |
| 0x2 | IIR（只读） | FCR（只写） | — | 0xC1 |
| 0x3 | LCR | LCR | — | 0x03 |
| 0x4 | MCR | MCR | — | 0x00 |
| 0x5 | LSR（只读） | — | — | 0x60 |
| 0x6 | MSR（只读） | — | — | 0x00 |
| 0x7 | SCR | SCR | — | 0x00 |

## 各寄存器详解

### THR / RBR（偏移 0x0，DLAB=0）

- **写 THR**：数据推入 TX FIFO（`tf_push`），发送器从 FIFO 取数据串行发出
- **读 RBR**：从 RX FIFO 弹出一个字节（`rf_pop`）

### DLL / DLM（偏移 0x0/0x1，DLAB=1）— 除数锁存器

16 位除数锁存器，波特率 = 时钟频率 / (16 × divisor)。

RTL 中 `dl` 是 16 位寄存器，写入后发送器/接收器的 16x 分频计数器使用新值。仿真环境下设 divisor=1 即可。

### IER（偏移 0x1，DLAB=0）— 中断使能

只有低 4 位有效：

| bit | 名称 | 说明 |
|-----|------|------|
| 0 | ERBFI | 接收数据可用中断 |
| 1 | ETBEI | 发送保持寄存器空中断 |
| 2 | ELSI | 接收线状态中断（OE/PE/FE/BI） |
| 3 | EDSSI | Modem 状态中断 |

```verilog
// uart_regs.v 写入逻辑
if (wb_we_i && wb_addr_i==`UART_REG_IE)
    ier <= #1 wb_dat_i[3:0];  // 只保留低 4 位
```

### IIR（偏移 0x2，只读）— 中断标识

| bit | 说明 |
|-----|------|
| 0 | IP：0=有中断挂起，1=无中断（反逻辑） |
| 3:1 | 中断类型编码 |
| 7:6 | FIFO 启用标志（固定 11） |

中断优先级通过 `if-else if` 链硬编码：

```
RLS (011) > RDA (010) > CTI (110) > THRE (001) > MS (000)
```

THRE 中断比较特殊——它是"电平触发"但需要"边沿行为"：用 `lsr5r` 延迟寄存器检测
TX FIFO 从非空→空的上升沿才置位，写 THR 或读 IIR 时清除。

```verilog
// uart_regs.v 中断仲裁
if (rls_int_pnd)
    iir[`UART_II_II] <= `UART_II_RLS;     // 最高优先级
else if (rda_int)
    iir[`UART_II_II] <= `UART_II_RDA;
else if (ti_int_pnd)
    iir[`UART_II_II] <= `UART_II_TI;
else if (thre_int_pnd)
    iir[`UART_II_II] <= `UART_II_THRE;
else if (ms_int_pnd)
    iir[`UART_II_II] <= `UART_II_MS;      // 最低优先级
else
    iir[`UART_II_IP] <= 1'b1;             // 无中断
```

### FCR（偏移 0x2，只写）— FIFO 控制

| bit | 说明 |
|-----|------|
| 0 | FIFO Enable（硬件固定启用，写入被忽略） |
| 1 | RX FIFO Reset（写 1 清空，**自清零**） |
| 2 | TX FIFO Reset（写 1 清空，**自清零**） |
| 7:6 | RX FIFO 触发级别：00=1字节, 01=4, 10=8, 11=14 |

bit[1] 和 bit[2] 是脉冲信号，写 1 后下一拍自动回 0。只有 bit[7:6] 被持久存储。

```verilog
// uart_regs.v FCR 写入
if (wb_we_i && wb_addr_i==`UART_REG_FC) begin
    fcr <= #1 wb_dat_i[7:6];   // 只保存 trigger level
    // bit[1]/bit[2] 产生单拍脉冲，下一拍自动清零
end
```

### LCR（偏移 0x3）— 线路控制

| bit | 说明 |
|-----|------|
| 1:0 | 数据位数：00=5, 01=6, 10=7, 11=8 |
| 2 | 停止位：0=1位, 1=1.5/2位 |
| 3 | 奇偶校验使能 |
| 4 | 偶校验选择 |
| 5 | Stick Parity |
| 6 | Break Control（强制 TX 输出低电平） |
| 7 | **DLAB** — 控制地址 0x0/0x1 的映射目标 |

复位值 0x03 = 8 数据位、1 停止位、无校验、DLAB=0。

**DLAB 机制在读写路径中的体现**：

```verilog
// 写路径
case (wb_addr_i)
    `UART_REG_TR: if (lcr[`UART_LC_DL])
                      dl[`UART_DL1] <= wb_dat_i;  // DLAB=1 → 写 DLL
                  // else → 写 THR（推入 TX FIFO）
    `UART_REG_IE: if (lcr[`UART_LC_DL])
                      dl[`UART_DL2] <= wb_dat_i;  // DLAB=1 → 写 DLM
                  else
                      ier <= wb_dat_i[3:0];       // DLAB=0 → 写 IER

// 读路径
    `UART_REG_RB: if (lcr[`UART_LC_DL])
                      wb_dat_o <= dl[`UART_DL1];  // DLAB=1 → 读 DLL
                  // else → 读 RBR（从 RX FIFO 弹出）
    `UART_REG_IE: if (lcr[`UART_LC_DL])
                      wb_dat_o <= dl[`UART_DL2];  // DLAB=1 → 读 DLM
                  else
                      wb_dat_o <= ier;            // DLAB=0 → 读 IER
```

### LSR（偏移 0x5，只读）— 线路状态

| bit | 名称 | 来源 | 读清？ |
|-----|------|------|--------|
| 0 | DR | RX FIFO 非空 | 否（实时状态） |
| 1 | OE | RX FIFO 溢出 | 是 |
| 2 | PE | 奇偶校验错 | 是 |
| 3 | FE | 帧错误 | 是 |
| 4 | BI | Break 中断 | 是 |
| 5 | THRE | TX FIFO 空 | 否（实时状态） |
| 6 | TEMT | 发送器完全空（FIFO空 + 移位寄存器空） | 否（实时状态） |
| 7 | EI | FIFO 中有错误数据 | 否（实时状态） |

复位值 **0x60** = THRE + TEMT，表示发送器空闲。

bit[1]~bit[4] 是"读清"的：读一次 LSR 后自动清零。bit[0]/[5]/[6]/[7] 实时反映硬件状态。

putch() 轮询的就是 bit[5] (THRE)：TX FIFO 空时为 1，写入 THR 后变 0，FIFO 发完再变 1。

### MCR（偏移 0x4）— Modem 控制

| bit | 说明 |
|-----|------|
| 0 | DTR |
| 1 | RTS |
| 2 | OUT1 |
| 3 | OUT2 |
| 4 | Loopback 模式（TX→RX 内部回环） |

### MSR（偏移 0x6，只读）— Modem 状态

低 4 位是 delta 位（读清），高 4 位是当前状态。ysyxSoC 中 modem 信号全部硬连线为 0。

### SCR（偏移 0x7）— 暂存寄存器

纯软件用途，硬件不使用，读写任意值。

## FIFO 实现（uart_tfifo.v / uart_rfifo.v）

- **TX FIFO**：16 深 × 8 位，标准读写指针 + 计数器
- **RX FIFO**：16 深 × 11 位（8 位数据 + 3 位错误标志：PE/FE/BI）
- 底层用 `raminfr.v` 实现双端口 RAM（Verilog reg 数组）
- 满/空判断用 5 位计数器（`UART_FIFO_COUNTER_W=5`），范围 0~16

## 发送器状态机（uart_transmitter.v）

```
IDLE → 起始位(0) → 数据位(LSB first, 5~8位) → [校验位] → 停止位(1/1.5/2) → IDLE
```

每个 bit 持续 16 个 enable 周期（enable 由除数分频产生）。发送器从 TX FIFO pop 数据，逐 bit 移出到 `stx_pad_o`。

## 接收器状态机（uart_receiver.v）

```
IDLE(等RX下降沿) → 起始位(采样中点) → 数据位 → [校验位] → 停止位 → 推入RX FIFO
```

16x 过采样，在每个 bit 的第 7~8 个 enable 周期采样中点值。检测到帧错误/校验错误/break 时，错误标志随数据一起写入 RX FIFO。

字符超时计数器：FIFO 有数据但未达触发级别，4 个字符时间后触发超时中断。

## APB 接口（uart_top_apb.v）

APB 2 周期握手：
- 周期 1：`psel=1, penable=0` → 产生 `reg_we`/`reg_re`
- 周期 2：`psel=1, penable=1` → `pready=1`，传输完成

地址取 `paddr[4:2]`（3 位），数据从 32 位 APB 总线中按 `paddr[1:0]` 提取 8 位。读回时 8 位数据复制 4 份填满 32 位总线。

## 软件初始化流程

```c
// 1. 设 DLAB=1，写除数
*(volatile uint8_t *)(UART_BASE + 0x03) = 0x80;  // LCR: DLAB=1
*(volatile uint8_t *)(UART_BASE + 0x00) = 1;     // DLL: divisor=1
*(volatile uint8_t *)(UART_BASE + 0x01) = 0;     // DLM: 0

// 2. 设 DLAB=0，配置 8N1
*(volatile uint8_t *)(UART_BASE + 0x03) = 0x03;  // LCR: 8bit, 1stop, no parity

// 3. 发送字符：轮询 LSR[5] 后写 THR
while (!(*(volatile uint8_t *)(UART_BASE + 0x05) & 0x20));
*(volatile uint8_t *)(UART_BASE + 0x00) = ch;
```
