# NPC 接入 ysyxSoC 改造进度

## 项目概述
将自研 RISC-V 5 级流水线处理器（DistributedCore）接入 ysyxSoC，替换原有的虚拟 SRAM/UART，使用 ysyxSoC 提供的真实外设。

## 目录结构
- `playground/src/` — Chisel 源码（CPU 核心）
- `build/ysyx_23060000.sv` — 生成的 CPU Verilog（需 sed 修正命名）
- `sim_soc/` — 接入 ysyxSoC 的仿真环境（Makefile + test_bench_soc.cpp）
- `ysyxSoC/` — ysyxSoC 所需文件（从上游精简复制，只含 build/perip/spec/src，~1.4MB，无 .git）
  - `build/ysyxSoCFull.v` — SoC 顶层（已替换 ysyx_00000000 → ysyx_23060000）**静态维护，直接编辑 .v**
  - `perip/` — 所有外设 Verilog（uart16550/spi/psram/sdram/flash 等）
  - `src/` — ysyxSoC Chisel 源码（仅供参考，**不在此重新生成**）
  - ⚠️ **重新生成 ysyxSoCFull.v 须去 `/home/lj/ysyx-workbench/ysyxSoC/` 执行 `make verilog`**
    - 依赖 rocket-chip/dependencies/diplomacy（Diplomacy 框架）+ firtool，本项目不含这些
    - 生成后手动复制 `build/ysyxSoCFull.v` 到本项目 `ysyxSoC/build/`，并重新 sed 替换模块名
- `am/` — AM ysyxsoc 平台文件（源文件在此，abstract-machine 对应位置为软链接）
  - `am/scripts/riscv32im-ysyxsoc.mk` — ARCH 入口
  - `am/scripts/platform/ysyxsoc.mk` — 平台配置
  - `am/src/riscv/ysyxsoc/start.S` — 启动代码
  - `am/src/riscv/ysyxsoc/trm.c` — TRM 运行时
  - `am/src/riscv/ysyxsoc/cte.c` — CTE 上下文切换（复制自 npc/cte.c）
  - `am/src/riscv/ysyxsoc/trap.S` — 异常入口（复制自 npc/trap.S）
  - `am/src/riscv/ysyxsoc/linker.ld` — 链接脚本
- ~~`/home/lj/ysyx-workbench/ysyxSoC/`~~ — 已迁移到 `ysyxSoC/`（项目内部）
- `/home/lj/ysyx-workbench/mycore/` — 旧的独立仿真环境（使用 DPI-C 虚拟内存，不再使用）

## 当前状态：SDRAM 位扩展完成（双颗粒 32-bit，cpu-tests 37/37）+ GPIO LED/NVBoard 初步接通

CPU 复位 PC 为 0x30000000（Flash），采用二级 Bootloader 启动：
1. FSBL（fsbl section，Flash XIP 执行）：搬运 SSBL 从 Flash(LMA) 到 SRAM(VMA)，跳转到 SRAM
2. SSBL（ssbl section，SRAM 执行）：搬运 .text+.rodata+.data 从 Flash(LMA) 到 PSRAM(VMA)，清零 .bss，跳转到 PSRAM 中的 _trm_init
3. 程序在 PSRAM(0x80000000) 执行，栈在 PSRAM 末尾（32KB），heap 在 .bss 之后到栈底

SDRAM 已完成位扩展：双颗粒并联（lo CHIP_SEL=0 + hi CHIP_SEL=1），数据总线 32-bit，BL=1，一次 READ/WRITE 命令完成 32-bit 传输。cpu-tests 37/37 全部通过。

前端目前已经接入第一版 `ICache`。结构位置在 `IFU` 和外部 `ifu_bus` 之间，默认配置为 4-way / 64 sets / 1 word per line（32-bit line，总容量 1KB），属于可配置参数化的 blocking icache。命中时直接返回；miss 时发起单拍 AXI 读，`R` 返回后若 `resp==OKAY` 则执行 refill，若 `resp!=0` 则把 fault 作为取指异常沿用现有 IFU/WBU 异常链路上报。当前实现仍是单未决 miss，不支持 hit-under-miss；redirect/flush 不直接清 cache，而是继续依赖 IFU 的 `epoch` 机制丢弃旧路径返回。

GPIO 已开始接入：`0x1000_2000` 的低 16 位寄存器可直接驱动 `externalPins_gpio_out[15:0]`。`sim_soc` 现提供两套仿真入口：普通命令走无 GUI 的 `test_bench_soc.cpp`；NVBoard 命令走 `test_bench_soc_nvboard.cpp`，并通过 `sim_soc/constr/ysyxSoCFull.nxdc` 将 `externalPins_gpio_out[15:0]` 绑定到 16 个 LED、`externalPins_gpio_in[15:0]` 绑定到 16 个拨码开关，便于后续补全 GPIO 输入寄存器与更多显示功能。

`riscv32im-ysyxsoc` 平台现已补齐 AM 的 UART 抽象（`AM_UART_CONFIG`/`AM_UART_TX`/`AM_UART_RX`），并支持和 `npc` 类似的 `mainargs` 注入流程：构建阶段通过 `insert-arg.py` 将参数字符串写入 bin 中的占位区，运行时 `trm.c` 直接把这段静态字符串传给 `main(const char *args)`。

键盘输入链路也已接通：`sim_soc/constr/ysyxSoCFull.nxdc` 将 `externalPins_ps2_clk/data` 绑定到 NVBoard 的 `PS2_CLK/PS2_DAT`；`ysyxSoC/perip/ps2/ps2_top_apb.v` 现按题面要求仅在 `0x10011000` 暴露 8 位扫描码数据寄存器（无数据返回 0，内部使用 FIFO 缓冲原始 PS/2 Set-2 字节流）；`am/src/riscv/ysyxsoc/input.c` 在软件侧把这些扫描码翻译成 AM 的 `AM_INPUT_KEYBRD` 事件，并正确处理 `0xE0/0xF0` 扩展序列。

本轮键盘问题的最终根因已经确认：最初的 `ps2_top_apb.v` 接收逻辑在检测到任意下降沿后就直接开始按 11 位盲收，未先等待合法的 start bit，因此帧边界容易错位，表现为 `data/parity` 看似偶尔正确但 `stop bit` 异常、按键事件不稳定，甚至只有在启动早期按下按键后才会在软件进入轮询时一次性“吐出”积压扫描码。最终修复方式是把接收器改为显式的 start-bit 驱动状态机：空闲时仅在采样边沿看到 `ps2_data==0` 才进入接收态，然后依次接收 data/parity/stop，并且只在 `start=0 && stop=1 && odd parity` 全部成立时将扫描码压入 FIFO；同时保持题面要求的寄存器语义，即 `0x10011000` 读扫描码、无数据返回 `0`。

本轮排查过程也值得记录：先确认 NVBoard 绑定与焦点分流是正确的（UART 焦点输入走 UART RX，非 UART 焦点输入走 PS/2）；随后通过临时日志分别验证了顶层 `PS2_CLK/PS2_DAT` 是否跳变、RTL 是否收到了完整帧、APB 是否发生读访问、以及 `AM_INPUT_KEYBRD` 是否被上层调用。排查中还顺手修复了 `klib` 的 `%c` 格式缺失问题，这解决了 UART 测试中 `Got (uart): %c (102)` 的显示异常，但它不是键盘问题的根因。最终在确认“线在动、帧能收、软件偶尔能消费积压数据”之后，收敛到 PS/2 接收状态机缺失 start-bit gating 这一根因，并据此完成稳定修复。

另一个容易造成“看起来像随机坏掉”的因素也已确认：`sim_soc/test_bench_soc_nvboard.cpp` 中 NVBoard 输入脚在初始化阶段必须保持协议定义的空闲电平，尤其是 `UART_RX=1`、`PS2_CLK=1`、`PS2_DAT=1`。如果在 `nvboard_init()` 前后把这些输入错误地置为 0，UART/PS2 接收器就可能在系统启动早期把线路误判为 start bit 或忙线，从而在软件真正开始轮询前积累伪输入，表现为“有时完全没反应，有时启动后突然吐出一串历史输入”。因此当前 NVBoard testbench 已改为在复位前显式置高这些空闲输入，并在释放复位前先执行一次 `nvboard_update()`，确保 DUT 上电后看到的是稳定空闲线路。

这轮调试还澄清了一个非常关键的边界：`sim_soc/*.cpp` 中的 `printf` 和 Verilog 里的 `$display/$write` 属于宿主机/仿真器侧日志，只会打印到运行仿真的终端，不会改变 DUT 软件路径；而 `am/src/riscv/ysyxsoc/*.c`、测试程序以及 `klib` 中的 `printf` 则属于 guest 侧输出，最终会通过 `trm.c` 里的 `putch()` 写入 UART16550 寄存器，从 DUT 的 UART TX 引脚真正发出去，因此 NVBoard 串口窗口也会收到这些字符。也就是说，在 `ioe.c` / `input.c` 中加入 `printf` 并不是“无副作用调试日志”，而是在被调试的 I/O 路径上额外注入一段 guest 串口输出，它会改变时序、污染串口观测，甚至制造 Heisenbug。后续若需要继续调 UART/键盘链路，应优先使用宿主机侧日志（C++ testbench / Verilog `$display`），避免在 guest 侧 I/O 抽象中直接 `printf`。

进一步实测验证表明：去掉 `am-tests/src/tests/keyboard.c` 中 `keyboard_test()` 开头那句 `printf("Try to press any key ...")` 后，`ps2-apb` 读访问和 `Got (kbd)` 立即恢复，说明程序此前并不是没有进入 `k` 分支，而是长时间停留在这条 guest 首打印所触发的 UART 输出过程中。结合宿主机侧日志可见，软件随后会在 `drain_keys()` 中持续读取 `UART_LSR`（因为 `has_uart=true`，`AM_UART_RX` 轮询本来就会疯狂刷 LSR），这属于预期现象；真正的问题是首条 guest 串口输出过早发生，导致键盘轮询启动被显著推迟甚至在调试期看起来像“完全没开始”。因此，后续调这类 I/O 组合测试时，应尽量避免在进入主轮询前打印长串 guest 字符串，尤其是在还同时打开大量宿主机侧 UART/PS2 调试日志的时候。

继续排查后，根因进一步收敛到 bootloader 与 C 运行时对同一 UART 的交接时序：`start.S` 的 SSBL 在跳转 `_trm_init` 前会输出 `M` 和换行，而 `_trm_init()` 一进入又立即重新执行一次 `uart_init()`。如果此时前一轮发送尚未完全空闲（尤其是 TX empty / transmitter empty 还未恢复），那么在发送器工作过程中重配 UART 寄存器就会把后续“刚进入 C 后的第一次连续多字符输出”带入异常窗口，表现为单字符通常还能过、但第二个字符开始就容易卡在 `putch()` 的 `LSR[5]` 等待里。相比之下，等系统稳定运行一段时间后再输出较长字符串通常不会触发这个窗口。针对这个问题，`trm.c` 现已在 `_trm_init()` 中先等待 UART 同时满足 `THRE|TEMT`，再执行 `uart_init()`，并在进入 `main()` 前再次等待发送器完全空闲，以避免 bootloader 输出与 C 运行时重新初始化发生重叠。

进一步定位后又确认了一点：`am-tests` 的 `keyboard_test()` 会先执行一条 guest 侧 `printf("Try to press any key ...")`，然后才读取 `AM_UART_CONFIG` / `AM_INPUT_CONFIG` 并进入轮询。由于这条 `printf` 同样走 DUT UART，因此如果 UART TX 在该时刻因为 LSR/THRE 状态迟迟未恢复，程序就会卡在这条首打印对应的 `putch()` 等待里，外部表现为已经能看到 PS/2 扫描码被硬件收到（`[ps2] scan=..`），但软件侧始终没有任何 `ps2-apb` 读访问。这个现象再次说明：调试时必须严格区分 host 侧日志与 guest 侧串口输出，后者会真实参与被测 I/O 路径。

RT-Thread 内核 ~185KB，运行在 PSRAM。CTE 支持已添加（cte.c + trap.S），协作式调度（ecall yield）。
栈从 SRAM 移到 PSRAM（RT-Thread 线程栈需求远超 SRAM 8KB），SRAM 仅用于 SSBL 临时执行。

PSRAM 已实现 QPI 模式：控制器复位后先用 1-bit SPI 发 35h 切换颗粒到 QPI，之后 CMD/ADDR/DATA 全部 4-bit。

NEMU（TARGET_SHARE 模式）已彻底去掉 pmem，改为独立地址空间：mrom_data(4KB) + sram_data(8KB) + flash_data(16MB) + psram_data(4MB)，guest_to_host 按地址分派到对应数组。

**RT-Thread 仿真状态**：RT-Thread 内核 ~185KB bin 编译通过，但 verilator 仿真未能验证——从 Flash XIP 搬运 185KB 到 PSRAM 所需仿真周期过多，实际运行不可行。RT-Thread 移植的正确性目前不可知，需要后续通过其他手段（如上板或优化仿真速度）验证。

## 开发规则
- **文档同步**：每项任务完成后，必须及时更新 CLAUDE.md（当前状态、已完成工作、开发历程等相关章节）
- **Git 提交**：commit 后顺手 push
- **linker.ld 内存布局注释**：每次修改 linker.ld 时，同步更新文件顶部的 ASCII 内存布局图

## 已完成的工作

### 1. AXI4 接口改造（全链路原生 AXI4）
- `common/AXI4.scala` — 完整 AXI4 接口定义（id/len/size/burst/last）
- `corewithbus/IFU.scala` — AR 通道 id=0, len=0, size=2, burst=1, 复位 PC=0x30000000（Flash XIP）
  - inst_queue（Queue, 深度4）缓冲 R 通道响应，r.ready 不依赖下游流水线，防止死锁
- `corewithbus/LSU.scala` — AR/AW 通道 id=1, size 根据 func 动态设置（lb=0, lh=1, lw=2）

### 1.1 第一版 ICache（IFU 前端缓存）
- `corewithbus/ICache.scala` — 新增可配置 blocking icache
  - 参数：`ICacheConfig(addrBits, dataBits, nSets, nWays, lineBytes, cacheableRegions)`
  - 当前默认值：`nSets=64`，`nWays=4`，`lineBytes=4`
  - 地址拆分：`tag | index | byteOffset`，默认 32-bit 地址下对应 `tag[31:8] / index[7:2] / offset[1:0]`
  - 存储体：`validArray + tagArray + dataArray + rrPtr`
  - 替换策略：优先填空 way，否则按每组 round-robin 指针替换
  - miss 路径：单未决 miss，AXI `AR(id=0,len=0,size=2,burst=INCR)` 发起 1 beat refill；`R` 返回后若 `resp==0` 则写 cache，否则只向上返回异常
  - cacheable 判定：当前默认缓存 `0x3000_0000`（Flash XIP）和 `0xA000_0000`（SDRAM execute）两个高位区域；其余地址走 bypass read，不分配 cache
- `corewithbus/IFU.scala` — 不再自己实现 AXI 取指状态机，改为：
  - `pc_reg` 通过 `icache.io.cpu.req` 发起取指
  - `meta_queue` 仍记录 `{pc, epoch}`，在请求被 icache 接收时入队
  - `inst_queue` 改为接收 `icache.io.cpu.resp(data, exception)`
  - redirect 后仍沿用 `epoch` 过滤旧 miss/旧命中返回，不直接 flush icache 内容

### 1.2 构建环境补齐（mill launcher + JDK）
- 仓库根目录新增 Unix 版 `mill` launcher 与兼容入口 `millw`，读取 `.mill-version` 自动解析/下载对应版本的 Mill
- `Makefile` 现默认优先调用仓库内 `./mill`
- 当前开发机已安装 `openjdk-17-jdk-headless`，可直接在 Linux/WSL 侧运行 `./mill -i playground.compile`
- 本轮已用 `./mill -i playground.compile` 验证 `ICache.scala + IFU.scala` 通过 Scala/Chisel 编译

### 2. CLINT（保留在 CPU 内部）
- `core/Axi4CLINT.scala` — AXI4 接口，地址范围 0x0200_0000~0x0200_ffff
- mtime 低32位 = 0x0200_BFF8，高32位 = 0x0200_BFFC

### 2.5 ebreak DPI-C 终止机制
- `core/CSR.scala` — SimEbreak BlackBox（HasBlackBoxInline），ebreak 时调用 DPI-C sim_ebreak()
- `sim_soc/test_bench_soc.cpp` — sim_ebreak() 设置 flag，主循环检测后提前退出

### 3. DistributedCore
- `top/top.scala` — 直接暴露 ifu_bus 和 lsu_bus 两个 AXI4Interface IO 端口
- 流水线内部逻辑不变

### 4. ysyx_23060000 顶层模块
- `top/ysyx_23060000.scala` — 符合 cpu-interface.md 规范
- 内部：DistributedCore + AXI4CLINT
- LSU 总线路由：CLINT 地址(高16位==0x0200)走内部，其余走外部 master
- IFU 和 LSU(非CLINT) 仲裁共享一个 AXI4 Master（LSU 优先）
- R 通道路由：根据 r.bits.id 区分（IFU id=0, LSU id=1）
- Slave 接口输出全部赋 0

### 5. Verilog 生成 + sed 修正
已集成到 `sim_soc/Makefile` 的 `verilog` 目标：
```bash
cd sim_soc && make verilog
```
自动完成：mill 生成 → 去掉 `_bits_` → 合并握手信号名（`aw_valid` → `awvalid`）→ 清理 BlackBox 资源列表

### 6. 仿真环境（sim_soc/）
- `sim_soc/Makefile` — verilator 编译，顶层 ysyxSoCFull，含 --timescale --no-timing --trace-fst --autoflush
  - `verilog` 目标：mill 生成 + sed 信号名修正（一条命令完成）
  - YSYXSOC_HOME = `$(abspath ../../ysyxSoC)` （注意相对路径基于 sim_soc/）
  - 包含 ysyxSoC/perip 下所有 .v，include uart16550/rtl 和 spi/rtl
- `sim_soc/test_bench_soc.cpp` — 仿真驱动
  - argv[1]=bin（加载到 Flash 16MB 缓冲区），argv[2]=diff_so（可选）
  - flash_read DPI-C 供 flash.v 内部使用，mrom_read DPI-C 保留（SoC 硬件仍有 MROM 模块）
  - DPI-C sim_ebreak() 终止机制：CSR 检测到 ebreak 后通知 testbench 退出
  - 复位 10 周期后主循环（最多 100 万周期，ebreak 提前退出）
  - FST 波形输出到 obj_dir/ysyxSoCFull.fst

### 7. AM 运行时环境（riscv32im-ysyxsoc）
- 源文件在 `am/` 目录下，`abstract-machine/` 对应位置为软链接（绝对路径）
- `am/scripts/riscv32im-ysyxsoc.mk` — ARCH 入口（RV32IM + libgcc）
- `am/scripts/platform/ysyxsoc.mk` — 平台配置（最小 TRM，无 IOE/CTE）
  - 新增 `run` 目标：调用 sim_soc/make run，透传 IMG/DIFFTEST/DIFF 参数
- `am/src/riscv/ysyxsoc/linker.ld` — 二级 Bootloader 链接脚本（PSRAM 执行）
  - FLASH (0x30000000, 16M): fsbl(VMA=LMA) + .ssbl/.text/.rodata/.data LMA
  - SRAM (0x0f000000, 8K): .ssbl VMA + 栈（向下增长到 SRAM 末尾）
  - PSRAM (0x80000000, 4M): .text/.rodata/.data VMA + .bss + heap（_bss_end~0x80400000）
  - .ssbl 使用 `> SRAM AT > FLASH`，.text/.rodata/.data 使用 `> PSRAM AT > FLASH`
- `am/src/riscv/ysyxsoc/start.S` — 二级 Bootloader
  - FSBL（fsbl section，Flash XIP）：搬运 SSBL 到 SRAM，`la + jalr` 跳转到 _ssbl_entry
  - SSBL（ssbl section，SRAM 执行）：搬运 .text+.rodata+.data 到 PSRAM，清零 .bss，`la + jalr` 跳转到 _trm_init
- `am/src/riscv/ysyxsoc/trm.c` — TRM 运行时
  - putch() 写 UART 0x10000000（sb 指令）
  - halt() 通过 ebreak 退出
  - 支持 `mainargs`：构建阶段将参数字符串注入 bin，占位区在运行时作为 `main(const char *args)` 的实参
- **软链接约定**：后续新增 AM ysyxsoc 相关文件，先在 `am/` 下创建，再去 `abstract-machine/` 对应位置加软链接（绝对路径）

### 8. ysyxSoCFull.v 模块名替换
- `ysyxSoC/build/ysyxSoCFull.v` 第 1465 行
- `ysyx_00000000` → `ysyx_23060000`（已用 sed 完成）

### 9. DiffTest（DPI-C 方案）
- `core/SimDebug.scala` — SimDifftest BlackBox，内联 Verilog 调用 sim_set_gpr/sim_difftest
- `top/top.scala` — DistributedCore 中实例化 SimDifftest，连接 debug_regs/debug_csr
- `sim_soc/test_bench_soc.cpp` — DiffTest 逻辑（`#ifdef DIFFTEST_ON`）
  - CPU_state 结构体与 NEMU 一致（注意 CSR 字段顺序：mtvec, mepc, mstatus, mcause）
  - NPC debug_csr 顺序：[0]=mcause, [1]=mepc, [2]=mstatus, [3]=mtvec
  - DPI-C: sim_set_gpr() 设置 GPR, sim_difftest() 提交指令
  - init_difftest(): dlopen NEMU .so → difftest_init → memcpy Flash → regcpy 同步初始状态
  - 主循环每拍检查 difftest_commit，比较 GPR/PC/CSR
- `sim_soc/Makefile` — DIFFTEST=1 启用，LDFLAGS 加 -ldl，run 目标接受 DIFF 参数
- NEMU 配置：`.config` 中 `CONFIG_TARGET_SHARE=y`（非 NATIVE_ELF），编译产物为 .so
  - `nemu/src/memory/paddr.c` — TARGET_SHARE 下独立地址空间：mrom_data(4KB) + sram_data(8KB) + flash_data(16MB) + psram_data(4MB)，无 pmem；guest_to_host 按地址分派；paddr_read/paddr_write 统一走 in_xxx + host_read/host_write
  - `nemu/include/memory/paddr.h` — TARGET_SHARE 下去掉 PMEM_LEFT/PMEM_RIGHT/in_pmem，RESET_VECTOR=0x30000000u
  - `nemu/src/isa/riscv32/init.c` — 统一用 RESET_VECTOR（TARGET_SHARE 下为 0x30000000），跳过内置镜像
  - `nemu/src/device/io/mmio.c` — TARGET_SHARE 下跳过 in_pmem 重叠检查

### 10. 异常处理机制（统一 WBU commit 点）
- 架构：IFU(fault) → IDU(exception) → EXU(透传) → WBU(检测) → CSR(exc_in注入) → mtvec redirect + flush_all
- `corewithbus/IDU.scala` — out bundle 新增 `exception: Valid(UInt(32.W))`，fault 时走 ALU 空路径（不再伪装 CSR jmp）
- `corewithbus/EXU.scala` — in/wbuOut 透传 exception；新增 `exc_in`/`mtvec_out` IO 暴露 CSR 异常注入端口
- `core/CSR.scala` — 新增 `exc_in` 端口（写 mcause/mepc/mstatus）和 `mtvec_out` 输出；移除旧 `is_inst_access_fault` 逻辑
- `corewithbus/WBU.scala` — 检测 EXU exception 和 LSU fault，输出 `exc_valid`/`exc_cause`/`exc_pc`；异常时抑制寄存器写回
- `top/top.scala` — WBU→CSR 异常连线，WBU 异常 redirect 优先于 EXU 跳转，`flush_all` 冲刷全流水线 + order_q
- `essentials/const.scala` — CauseCode: INST_ACCESS_FAULT(1), LOAD_ACCESS_FAULT(5), STORE_ACCESS_FAULT(7)
- `corewithbus/IFU.scala` — inst_queue 存 (data, exception) 对，exception = r.resp =/= 0
- `corewithbus/LSU.scala` — out 端口 exception 字段（resp 检测），已接入 WBU 异常路径

### 11. mem-test 内存访问测试
- `am/src/riscv/ysyxsoc/linker.ld` — 调整布局：栈移到 SRAM 末尾(4KB)，堆在 .bss 和栈之间
  - 新布局：.data/.bss → _heap_start → 堆区 → _stack_top(0x0f001000) → 栈(4KB) → _stack_pointer(0x0f002000)
- `am/src/riscv/ysyxsoc/trm.c` — heap 范围改用 linker 符号 (_heap_start, _stack_top)，移除硬编码 SRAM_END
- `am-kernels/tests/cpu-tests/tests/mem-test.c` — 堆区 8/16/32 位写入-读回校验，DiffTest 通过

### 12. UART16550 初始化 + putch() 轮询
- `am/src/riscv/ysyxsoc/trm.c` — uart_init()：DLAB=1 设 divisor=1，DLAB=0 设 8N1；putch() 轮询 LSR[5](THRE) 后写 THR；_trm_init() 调用 uart_init()
- `nemu/src/memory/paddr.c` — TARGET_SHARE 分支添加 UART 地址范围(0x10000000, 8B)：读 LSR 返回 0x60(THRE+TEMT)，其余返回 0；写静默忽略

### 13. Flash 读取（FAST_FLASH + DPI-C）→ 已被真实 SPI 替代
- 旧方案：`FAST_FLASH` 宏绕过 SPI 时序，DPI-C flash_read 直读
- 现已注释 `FAST_FLASH`，改用真实 SPI 协议（见 §14）
- `sim_soc/test_bench_soc.cpp` — flash_read DPI-C 仍保留（flash.v 内部 flash_cmd 模块使用）
- `nemu/src/memory/paddr.c` — Flash 地址映射保留
- `am-kernels/tests/cpu-tests/tests/flash-test.c` — 旧测试（需 FAST_FLASH 才能运行）

### 14. bitrev SPI slave 模块 纯测试练手模块，本身与FLASH毫无关系
- `ysyxSoC/perip/bitrev/bitrev.v` — 位翻转 SPI slave：接收 8 bit → 位翻转 → 发送 8 bit（总 16 bit）
  - MSB first，posedge sck 采样/输出，SS 低有效，空闲 MISO=1
  - SoC 中连接在 SPI SS[7]
- `am-kernels/tests/cpu-tests/tests/bitrev-test.c` — SPI 驱动测试：DIVIDER=0, SS=0x80, CHAR_LEN=16, ASS|Tx_NEG|GO

### 15. 软件驱动 SPI Flash 读取
- `ysyxSoC/perip/spi/rtl/spi_top_apb.v` — 注释 `FAST_FLASH`，启用真实 SPI master（spi_top）
- `am-kernels/tests/cpu-tests/tests/spi-flash-test.c` — 64-bit SPI 传输（8 cmd + 24 addr + 32 data）
  - TX_1 = {0x03, addr[23:0]}，TX_0 = 0（dummy），CHAR_LEN=64 #dummy指的是无意义的数据，因为SPI只工作在全双工，所以此时接受也必须发送
  - flash.v 内部 data_bswap，软件需 bswap32(RX_0) 还原
  - 读 256 个 word 校验已知模式通过
- `nemu/src/memory/paddr.c` — 添加 SPI 寄存器地址范围(0x10001000, 0x1000)：读返回 0（GO=0），写忽略

### 16. Flash 启动（flash-loader）— 已被 XIP 取代，文件已删除
- 旧方案：flash-loader.c 从 MROM 软件驱动 SPI 读 flash 到 SRAM 再跳转
- 已删除：`flash-loader.c`、`char-test-sram.c`、`sram.ld`、`char-test.c`
- 现在用 XIP：xip-jump(MROM) → char-test-flash(Flash 直接执行)

### 17. XIP Flash（硬件状态机 + Execute In Place）
- `ysyxSoC/perip/spi/rtl/spi_top_apb.v` — XIP 状态机（8 states）
  - 地址判断：`is_flash = (in_paddr >= 0x30000000) && (in_paddr <= 0x3fffffff)`
  - 状态机：IDLE→WR_TX1→WR_TX0→WR_SS→WR_CTRL→POLL→RD_RX0→DONE
  - Wishbone MUX：IDLE 时 APB 直连 spi_top（软件驱动 SPI 仍可用），XIP 时状态机驱动
  - APB pready：IDLE 时来自 wb_ack，XIP 时仅在 DONE 状态为 1
  - bswap32 还原字节序，写保护（flash 地址 + pwrite → $fatal）
- `am-kernels/tests/cpu-tests/tests/xip-flash-test.c` — XIP 读取测试：指针直读 0x30000000，256 word 校验通过

### 18. Flash 直接启动（去 MROM 化）
- IFU 复位 PC 改为 0x30000000，CPU 复位后直接从 Flash XIP 取指
- linker.ld：FLASH(0x30000000, 16M) 替代 MROM，.text/.rodata/.data LMA 全部在 FLASH
- test_bench_soc.cpp：bin 加载到 flash_data（不再加载到 mrom_data），命令行简化为 argv[1]=bin argv[2]=diff_so
- DiffTest：init_difftest memcpy 到 0x30000000，NPC/NEMU 初始 PC=0x30000000
- NEMU paddr.c：flash 地址可写（支持 difftest_memcpy 初始化）
- ysyxsoc.mk 新增 `run` 目标，支持 AM 框架下 `make run` 直接运行仿真
- 已删除旧文件：xip-jump.c、char-test-flash.c、flash.ld、mrom.ld

### 19. PSRAM 颗粒仿真模型 + QPI 模式
- `ysyxSoC/perip/psram/psram.v` — 替换空桩，实现 QSPI/QPI 颗粒行为模型
  - 4MB 存储阵列（22-bit 地址），DPI-C 稀疏存储
  - 状态机：IDLE→CMD→ADDR→DUMMY→RDATA/WDATA，ce_n 上升沿异步复位
  - QSPI 模式：CMD 1-bit/cycle×8，ADDR/DATA 4-bit/cycle
  - QPI 模式：CMD 4-bit/cycle×2，ADDR/DATA 4-bit/cycle（35h 命令切换）
  - sck posedge 采样输入，sck negedge 驱动读数据输出
  - **addr_reg 高位截断修复**：DPI-C 调用统一用 `{8'b0, addr_reg[23:0]}`，防止 S_ADDR 移位残留高位污染 32-bit 地址
- `ysyxSoC/perip/psram/efabless/EF_PSRAM_CTRL.v` — 升级为 QPI 版
  - 新增 `PSRAM_INIT` 模块：1-bit SPI 发送 35h（8 cycle），done 信号通知 wb 层
  - `PSRAM_READER` QPI 版：CMD 2 cycle（原 8），FINAL_COUNT = 13+size*2（原 19+size*2）
  - `PSRAM_WRITER` QPI 版：CMD 2 cycle（原 8），FINAL_COUNT = 7+size*2（原 13+size*2）
- `ysyxSoC/perip/psram/efabless/EF_PSRAM_CTRL_wb.v` — 主 FSM 加 ST_INIT 状态
  - 复位后 state=ST_INIT，驱动 PSRAM_INIT 发 35h，done 后进入 ST_IDLE
  - MUX：ST_INIT 时用 MI，ST_WAIT+wb_we 时用 MW，否则用 MR
- `am/src/riscv/ysyxsoc/linker.ld` — heap 改为 PSRAM（0x80000000~0x80400000，完整 4MB）
- `am/src/riscv/ysyxsoc/trm.c` — heap 范围改用 `_heap_end` 符号（原 `_stack_top`）
- mem-test 4KB PSRAM 校验通过（8/16/32-bit），cpu-tests 38/40 通过（spi-flash-test/bitrev-test 预期失败）

### 20. NEMU 独立地址空间改造（去 pmem）
- `nemu/src/memory/paddr.c` — TARGET_SHARE 下彻底删除 pmem/pmem_read/pmem_write
  - 独立数组：mrom_data(4KB) + sram_data(8KB) + flash_data(16MB) + psram_data(4MB)
  - guest_to_host 按地址分派到对应数组（flash/psram/sram/mrom）
  - paddr_read/paddr_write 统一走 in_xxx + host_read/host_write，无 pmem 兜底
  - UART 读返回 LSR=0x60，SPI 读返回 0，写静默忽略
- `nemu/include/memory/paddr.h` — TARGET_SHARE 下去掉 PMEM_LEFT/PMEM_RIGHT/in_pmem，RESET_VECTOR=0x30000000u
- `nemu/src/isa/riscv32/init.c` — restart() 统一用 RESET_VECTOR
- `nemu/src/device/io/mmio.c` — TARGET_SHARE 下跳过 in_pmem 重叠检查
- 非 TARGET_SHARE 路径完全不变（传统 pmem 模式）
- cpu-tests 38/40 DiffTest 通过（spi-flash-test/bitrev-test 预期失败）

### 21. RT-Thread 移植（CTE + linker.ld 合并 + CLINT 映射）
- `am/src/riscv/ysyxsoc/cte.c` — 复制自 npc/cte.c，M-mode 异常处理（ecall yield/timer IRQ）
- `am/src/riscv/ysyxsoc/trap.S` — 复制自 npc/trap.S，上下文保存/恢复
- `abstract-machine/am/src/riscv/ysyxsoc/cte.c` / `trap.S` — 绝对路径软链接
- `am/scripts/platform/ysyxsoc.mk` — AM_SRCS 添加 cte.c/trap.S；LDFLAGS 过滤掉 extra.ld（已合并到 linker.ld）
- `am/src/riscv/ysyxsoc/linker.ld` — 合并 RT-Thread extra.ld 的 section：
  - `.data.extra`（FSymTab/VSymTab/.rti_fn/UtestTcTab/am_apps.data）放在 .rodata 和 .data 之间，`> PSRAM AT > FLASH`
  - `.bss.extra`（am_apps.bss）放在 .data 和 .bss 之间，`_bss_start` 移到 .bss.extra 开头
  - 栈从 SRAM 移到 PSRAM 末尾（32KB），SRAM 仅用于 SSBL
  - heap 从 _bss_end 到 _stack_pointer - 32K
- `nemu/src/memory/paddr.c` — TARGET_SHARE 添加 CLINT 地址范围(0x02000000, 64KB)：读返回 0，写忽略
- RT-Thread 内核 ~185KB bin，编译通过
- **仿真未验证**：185KB 从 Flash XIP 搬运到 PSRAM 所需仿真周期过多，verilator 仿真不可行，移植正确性待后续验证

### 22. Bootloader 诊断输出（汇编 UART）
- `am/src/riscv/ysyxsoc/start.S` — 在 bootloader 各阶段添加 UART 字符输出，用于诊断搬运进度
  - 汇编宏 `uart_init_asm`：FSBL 开头初始化 UART16550（DLAB 设 divisor=1，8N1）
  - 汇编宏 `uart_putc`：轮询 LSR[5](THRE) 后写 THR
  - `F` — FSBL 开始执行（UART 初始化完成）
  - `S` — SSBL 已搬运到 SRAM，即将跳转
  - `M\n` — 程序已搬运到 PSRAM + .bss 已清零，即将跳转 _trm_init
- trm.c 中 uart_init() 会重复初始化，无影响
- dummy 测试验证通过：输出 `FSM` 后正常 PASS
### 23. SDRAM 颗粒仿真模型 + APB 控制器集成（单颗粒 16-bit，已被 §24 取代）
- `ysyxSoC/perip/sdram/sdram.v` — MT48LC16M16A2 SDR SDRAM 颗粒行为模型
  - 容量：256Mbit (16M x 16-bit)，4 banks x 8192 rows x 512 columns x 16-bit
  - 状态机：S_IDLE → S_ACTIVE → S_READ/S_WRITE → S_READ_DATA/S_WRITE → S_ACTIVE
  - 命令支持：NOP, ACTIVE, READ, WRITE, PRECHARGE, REFRESH, LOAD_MODE
  - DPI-C 稀疏存储（C++ `unordered_map<uint32_t, uint16_t>`），16-bit 数据单位
  - **写采样修复**：S_ACTIVE 收到 WRITE 命令当拍即采样第一个 beat（`write_sample` 信号），后续 beat 在 S_WRITE 采样
  - **读输出**：组合逻辑驱动（非寄存器），适配 Verilator 零延迟仿真
  - **行状态修复**：S_READ_DATA/S_WRITE burst 完成后回到 S_ACTIVE（行仍打开），而非 S_IDLE
- `ysyxSoC/perip/sdram/sdram_top_apb.v` — APB 封装 + sdram_axi_core 控制器
  - SDRAM_READ_LATENCY=3（补偿反相时钟 + 2 级采样流水线在 Verilator NBA 模型下的延迟）
  - APB 信号锁存（setup phase 锁存 addr/wdata/strb/write）
  - 三态状态机：ST_IDLE → ST_WAIT_ACCEPT → ST_WAIT_ACK
- `sim_soc/test_bench_soc.cpp` — SDRAM DPI-C 桩函数（sdram_read/sdram_write）
- SDRAM 地址空间：0xa0000000~0xbfffffff（CPU 视角）
- sdram-mem-test 通过 8/16/32-bit 写读校验，cpu-tests 40/40 全部通过

### 24. SDRAM 位扩展（16→32-bit，双颗粒并联）
- `ysyxSoC/perip/sdram/sdram.v` — 添加 `CHIP_SEL` 参数（0=lo/1=hi），lo/hi 颗粒各用独立 DPI-C 函数（sdram_lo_read/write、sdram_hi_read/write）；BL=1 时 WRITE 后回到 S_ACTIVE（行保持打开，支持控制器 row-hit 优化）
- `ysyxSoC/perip/sdram/core_sdram_axi4/sdram_axi_core.v` — 核心改造：
  - 数据总线 16→32-bit，DQM 2→4-bit，MODE_REG BL=1（原 BL=2）
  - 删除 STATE_WRITE1、data_buffer_q、dqm_buffer_q（不再需要两拍拼接）
  - 新增 `write_data_latch_q`/`write_dqm_latch_q`：在 STATE_IDLE 接受写请求时锁存，STATE_WRITE0 使用锁存值，避免 ACTIVATE/DELAY 期间 `inport_wr_i` 失效导致 DQM 全屏蔽
  - 地址位域修正：32-bit 位扩展后每列地址对应 4 字节，addr_col/bank/row 位域均右移 1 位
  - 读路径：删除第二级采样寄存器（sample_data_q），直接用 sample_data0_q；ACK 在 rd_q[SDRAM_READ_LATENCY] 触发
- `ysyxSoC/perip/sdram/sdram_top_apb.v` — dq 16→32-bit，dqm 2→4-bit；移除颗粒实例化（颗粒只在 ysyxSoCFull.v 顶层实例化）
- `ysyxSoC/build/ysyxSoCFull.v` — APBSDRAM 模块端口/顶层端口/内部 wire 扩宽；单颗粒替换为双颗粒（sdram_lo CHIP_SEL=0 + sdram_hi CHIP_SEL=1，共享所有控制信号，dq/dqm 各占一半）
- `sim_soc/test_bench_soc.cpp` — lo/hi 独立存储 map（`unordered_map<uint32_t, uint16_t>`），四个 DPI-C 函数
- sdram-mem-test 通过（8/16/32-bit），cpu-tests 37/37 全部通过

```bash
# 生成 Verilog（含 sed 修正）
cd sim_soc && make verilog

# 编译仿真器
cd sim_soc && make sim

# 编译 + 运行单个 AM 测试（自动调用 sim_soc 仿真）
cd /home/lj/ysyx-workbench/am-kernels/tests/cpu-tests
make ARCH=riscv32im-ysyxsoc ALL=dummy run

# 带 DiffTest 运行
make ARCH=riscv32im-ysyxsoc ALL=dummy run DIFFTEST=1 DIFF=/home/lj/ysyx-workbench/nemu/build/riscv32-nemu-interpreter-so

# 批量测试（编译+运行所有 cpu-tests）
make ARCH=riscv32im-ysyxsoc run

# 手动运行仿真（直接指定 bin）
cd sim_soc && make run IMG=/path/to/test.bin
cd sim_soc && make run IMG=/path/to/test.bin DIFFTEST=1 DIFF=/path/to/nemu.so

# 编译 NEMU .so（需要 CONFIG_TARGET_SHARE=y）
cd nemu && make ISA=riscv32 -j$(nproc)
```

## 下一步待办
1. RT-Thread 仿真验证（需解决 185KB 搬运耗时问题，或上板验证）
2. 定时器中断（CLINT mtime → 抢占式调度）
3. DiffTest 适配（CLINT mtime 同步问题）

## 临时修改（后续可恢复）
- `~/Templates/rt-thread-am/bsp/abstract-machine/integrate-am-apps.py` — 注释掉 `fceux-am`（NES 模拟器），其 ROM 数据占 ~2MB rodata，导致 bin 2.6MB，verilator 仿真搬运耗时不可接受。去掉后纯内核 ~144KB。需要时取消注释即可恢复。

## 未来优化点（功能稳定后再做）
- **EXU 拆分**：当前 EXU 混合了 Dispatch/Execute/Arbitration/Redirect/Serialization 五种职责，应拆为独立的 Issue/Dispatch + 各 FU 独立 + Writeback Arbiter
- **CSR 序列化放宽**：当前所有 CSR 类指令统一要求 `rob_empty && !mdu_locked`，实际只有 jmp 类（ECALL/EBREAK/MRET）需要序列化，普通 CSRRW/CSRRS/CSRRC 可以放宽

## 开发历程

1. 五级流水线基础架构（IFU/IDU/EXU/LSU/WBU），内部异步总线，DPI-C 虚拟内存
2. 仿真环境 + ebreak 终止 + DiffTest 初步通过
3. IFU 状态机完善，WBU 加入 EXU/LSU 仲裁保证顺序提交
4. RAW 前递 + Load-Use 暂停，meta_queue 实现 IFU 流水线 + epoch 冲刷，order_q 顺序退休
5. 跑通 RV32E 全部测试集
6. 独立 mycore，实现 M 扩展，通过 RV32IM 测试 + cpu-tests
7. 成功运行 rt-thread-am
8. AXI4-Lite 总线，RTL 原生 UART/TIMER（仍通过仿真环境）
9. 接入 ysyxSoC：AXI4 全链路改造，脱离 DPI-C 虚拟环境
10. UART 字符输出测试通过（char-test）
11. IFU/LSU 共享端口死锁修复（inst_queue + ID 路由）
12. AM 运行时 riscv32im-ysyxsoc，dummy/fib 通过
13. DiffTest 恢复（DPI-C 方案），Access Fault 异常（IFU 侧）
14. 异常机制重构：统一 WBU commit 点处理，移除 IDU 伪装 CSR jmp，LSU fault 接入 trap 路径
15. mem-test：linker.ld 布局调整（栈移末尾、堆可用），SRAM 8/16/32 位访存校验通过 DiffTest
16. mrom_read 地址对齐修复：DPI-C 未对齐到 4 字节边界导致 lbu 从 MROM 读错字节，string/crc32 DiffTest 失败。修复后 cpu-tests 35/35 全部通过
17. UART16550 初始化 + putch() 轮询：uart_init() 设 8N1/divisor=1，putch() 轮询 LSR THRE；NEMU 侧添加 UART 地址映射。cpu-tests 36/36 全部通过 DiffTest
18. Flash 读取：启用 FAST_FLASH 宏，实现 flash_read DPI-C（16MB 缓冲区 + 已知模式），NEMU 侧添加 flash 地址映射，flash-test 校验通过。cpu-tests 37/37 全部通过 DiffTest
19. bitrev SPI slave：实现位翻转模块（接收 8bit → 翻转 → 发送 8bit），bitrev-test 通过 SPI master 驱动校验通过
20. 软件驱动 SPI Flash：关闭 FAST_FLASH，64-bit SPI 传输（0x03 + addr + dummy），bswap32 还原字节序，spi-flash-test 256 word 校验通过。NEMU 添加 SPI 地址映射
21. Flash 启动：flash-loader 从 MROM 执行，SPI 读 flash 到 SRAM 并跳转，char-test-sram 输出 'A\n' + ebreak 成功
22. XIP Flash：spi_top_apb.v 实现 8 状态 XIP 硬件状态机，CPU 读 0x30000000 自动完成 SPI 传输。xip-flash-test 256 word 校验通过，xip-jump 从 MROM 跳转到 flash 执行 char-test-flash 成功
23. Flash 直接启动（去 MROM 化）：IFU 复位 PC 改为 0x30000000，linker.ld 改用 FLASH(16M) 替代 MROM(4KB)，bin 直接加载到 Flash，DiffTest 适配（NEMU PC/memcpy 改为 0x30000000），ysyxsoc.mk 新增 run 目标支持 AM 框架下 make run 和批量测试
24. Flash XIP 连续取指修复 + DiffTest 全量回归：
    - SPI XIP 状态机修复：S_IDLE 加 in_penable 条件防止非 APB access phase 误触发；扩展为 4-bit 状态机加 S_CLR_SS 状态确保每次传输后清除片选
    - 测试框架修复：超时返回非零退出码，只有 ebreak 才判 PASS
    - IFU/LSU 仲裁修复：LSU 写不阻塞 IFU 读，IFU 读不阻塞 LSU 写
    - IFU 复位抑制：ar_state 状态机限制 outstanding 为 1，reset 时抑制 arvalid
    - MAX_CYCLES 默认调至 2000 万（Flash XIP 每条指令 ~150 周期）
    - cpu-tests 38/40 通过 DiffTest（spi-flash-test/bitrev-test 因 XIP 占用 SPI 预期失败）
25. PSRAM 颗粒仿真模型 + QPI 模式：
    - psram.v 实现 QSPI/QPI 颗粒行为模型（4MB，EBh/38h/35h 命令）
    - EF_PSRAM_CTRL.v 新增 PSRAM_INIT 模块，READER/WRITER 升级为 QPI（CMD 2 cycle）
    - EF_PSRAM_CTRL_wb.v 主 FSM 加 ST_INIT 状态，复位后先发 35h 切换 QPI
    - linker.ld heap 改为 PSRAM（0x80000000），trm.c 使用 _heap_end 符号
    - mem-test 4KB PSRAM 校验通过，cpu-tests 39/40（bitrev-test 预期失败）
26. NEMU 独立地址空间 + PSRAM 颗粒 addr_reg 修复：
    - NEMU paddr.c：TARGET_SHARE 下删除 pmem，改为独立数组（mrom/sram/flash/psram_data）
    - guest_to_host 按地址分派，paddr_read/paddr_write 统一 in_xxx 模式
    - paddr.h：TARGET_SHARE 下去掉 PMEM_LEFT/PMEM_RIGHT/in_pmem，RESET_VECTOR=0x30000000u
    - init.c：restart() 统一用 RESET_VECTOR
    - mmio.c：TARGET_SHARE 下跳过 in_pmem 重叠检查
    - psram.v addr_reg 高位污染修复：S_ADDR 移位只填充低 24 位，但 addr_reg 是 32 位，高 8 位残留上次操作值。DPI-C 调用传完整 32 位导致写入/读取地址不一致。修复：所有 DPI-C 调用统一用 {8'b0, addr_reg[23:0]}
    - 此 bug 之前被 128MB pmem 掩盖（NEMU 侧不经过 PSRAM DPI-C），改造后暴露
    - cpu-tests 38/40 DiffTest 通过（spi-flash-test/bitrev-test 预期失败）
27. SRAM 执行 + PSRAM 4MB heap 改造：
    - linker.ld：entry section 单独放 Flash（VMA=LMA），.text/.rodata/.data 改为 `> SRAM AT > FLASH`
    - 新增 _sram_start/_sram_lma 符号，搬运范围从只搬 .data 扩大到 .text+.rodata+.data
    - _heap_end 从 0x80001000(4KB) 扩大到 0x80400000(4MB)，去掉 _stack_top
    - start.S：bootloader 搬运整个 SRAM 区域，用 `la + jalr` 绝对跳转到 SRAM（替代 PC-relative 的 call）
    - 程序从 SRAM 单周期取指执行，大幅提升性能（原 Flash XIP 每条指令 ~150 周期）
28. 二级 Bootloader（FSBL+SSBL）+ 程序运行在 PSRAM：
    - linker.ld：三段内存（FLASH/SRAM/PSRAM），fsbl(VMA=LMA=Flash)，ssbl(VMA=SRAM, LMA=Flash)，.text/.rodata/.data(VMA=PSRAM, LMA=Flash)
    - start.S：FSBL(fsbl section) 搬 SSBL 到 SRAM → SSBL(ssbl section) 搬程序到 PSRAM + 清零 .bss → 跳转 _trm_init
    - 程序突破 SRAM 8KB 限制，可使用完整 4MB PSRAM；栈仍在 SRAM（快速）；heap 从 _bss_end 到 0x80400000
29. RT-Thread 移植到 ysyxsoc（CTE 协作式调度）：
    - 添加 CTE 支持：cte.c（ecall yield/timer IRQ）+ trap.S（上下文保存恢复），复制自 npc 平台
    - linker.ld 合并 RT-Thread extra.ld：.data.extra（FSymTab/VSymTab/.rti_fn/UtestTcTab）+ .bss.extra（am_apps.bss），避免 INSERT BEFORE 与 AT > FLASH 冲突
    - 栈从 SRAM 移到 PSRAM 末尾 32KB（RT-Thread 线程栈需求远超 SRAM 8KB）
    - ysyxsoc.mk 过滤掉 extra.ld（LDFLAGS filter-out），避免重复定义 section
    - NEMU paddr.c 添加 CLINT 地址映射（0x02000000, 64KB）：读返回 0，写忽略
    - RT-Thread 内核 ~185KB bin 编译通过
30. Bootloader 诊断输出 + RT-Thread 仿真受限确认：
    - start.S 添加汇编 UART 宏（uart_init_asm + uart_putc），FSBL/SSBL 各阶段输出 F/S/M 字符
    - dummy 测试验证通过：输出 `FSM` 后正常 PASS（51952 cycles）
    - RT-Thread 185KB bin 仿真不可行：Flash XIP 搬运到 PSRAM 所需周期过多，移植正确性待后续验证
31. SDRAM 颗粒仿真模型 + 控制器时序调试：
    - sdram.v 实现 MT48LC16M16A2 行为模型（S_IDLE/S_ACTIVE/S_READ/S_READ_DATA/S_WRITE 状态机）
    - 写时序修复：WRITE 命令当拍即采样第一个 beat（wire write_sample），不等状态机转移
    - 读时序修复：dq 输出改组合逻辑（避免 Verilator NBA 竞争），SDRAM_READ_LATENCY=3
    - 行状态修复：burst 完成后回到 S_ACTIVE（非 S_IDLE），修复背靠背写读失败（控制器 row-hit 跳过 ACTIVATE 时芯片在 S_IDLE 忽略 READ）
    - sdram-mem-test（8/16/32-bit 256B 校验）通过，cpu-tests 40/40 全部通过
32. SDRAM 位扩展（16→32-bit，双颗粒并联）：
    - sdram.v 添加 CHIP_SEL 参数，lo/hi 颗粒各用独立 DPI-C；BL=1 时 WRITE 后回 S_ACTIVE（支持 row-hit）
    - sdram_axi_core.v：数据总线 32-bit，DQM 4-bit，MODE_REG BL=1，删除 STATE_WRITE1/data_buffer_q/dqm_buffer_q
    - 关键 bug 修复：STATE_IDLE 锁存 write_data/write_dqm，避免 ACTIVATE/DELAY 期间 inport_wr_i 失效导致 DQM 全屏蔽
    - 地址位域修正：32-bit 每列 4 字节，addr_col/bank/row 位域右移 1 位
    - ysyxSoCFull.v 双颗粒实例化（sdram_lo + sdram_hi），test_bench_soc.cpp lo/hi 独立存储 map
    - cpu-tests 37/37 全部通过

## 已清理的旧文件（已删除，可通过 git 历史恢复）
- `common/AXI4Lite.scala`、`common/SimpleBus.scala` — 旧总线协议
- `core/SoCTop.scala`、`core/RAM.scala`、`core/Axi4LiteUART.scala`、`core/Axi4LiteCLINT.scala` — 旧 SoC 路由和 DPI-C 虚拟外设
- `top/main.scala` — 旧入口点（已被 main_ysyxsoc.scala 替代）
- `sim_soc/char-test.c`、`sim_soc/char-test-sram.c`、`sim_soc/flash-loader.c`、`sim_soc/sram.ld` — 被 XIP 方案取代
- `sim_soc/xip-jump.c`、`sim_soc/char-test-flash.c`、`sim_soc/flash.ld`、`sim_soc/mrom.ld` — 被 Flash 直接启动取代（不再需要 MROM 跳板）
33. SDRAM 多 bank / 多行调试复盘（PSRAM 执行，SDRAM 仅做数据读写验证）：
    - 调试背景：为避免“程序本身搬到 SDRAM 后又覆盖测试区”影响结论，切回“程序运行在 PSRAM，单独用 `sdram-mem-test` 读写 SDRAM”的模式排查。
    - 缩点策略：先把 `sdram-mem-test` 从大范围扫描缩到 16KB、1KB，最终缩到只盯 `0xa0001000` 与 `0xa0003000` 两个地址；再加前缀写 / 前缀读扫描，确认问题不是简单边界越界，而是与 bank/row 状态切换有关。
    - 现象 1：`0xa0003000` 的 32-bit 模式值 `0x9e8c8ddf` 会出现在 `0xa0001000`；典型报错为 `!@00001000:9e8c8ddf/caa429df`，说明 `0x3000` 的内容覆盖到了 `0x1000`。
    - 现象 2：控制器日志显示 `cpu=a0003000 -> row=1 bank=2 col=0`，但芯片日志实际写入到了 `local=0x00000800 (row=0 bank=2 col=0)`，而不是期望的 `local=0x00001800`。
    - 坑 1（已修复）：`ysyxSoC/perip/sdram/sdram.v` 在 `S_ACTIVE` 下处理 `CMD_READ/CMD_WRITE` 时没有同步更新 `current_bank`。结果是 row-hit 场景下读写可能沿用上一次 bank，最小双地址复现（如 `0xa0000800` / `0xa0001004`）会直接串地址。修复：`CMD_READ` / `CMD_WRITE` 分支都补上 `current_bank <= ba`。
    - 坑 2（已修复）：`ysyxSoC/perip/sdram/sdram.v` 在 `S_ACTIVE` 下处理 `CMD_PRECHARGE` 时错误地关闭了 `row_open[current_bank]`，而不是当前命令指定的 `row_open[ba]`。这会在切 bank 时留下错误的 open-row 状态。修复：改为 `row_open[ba] <= 1'b0`，并同步 `current_bank <= ba`。
    - 坑 3（核心根因，已修复）：SDRAM 芯片模型在 `S_ACTIVE` 状态下根本不处理新的 `CMD_ACTIVE`。真实 SDRAM 允许“bank0 仍打开时，再 ACTIVATE bank2 的另一行”，控制器也是这么做的；但模型把这条 ACT 吃掉了，导致后续 `WRITE a0003000` 仍落在旧的 `row0`。修复：在 `S_ACTIVE` 增加 `CMD_ACTIVE` 分支，正确更新 `active_row[ba] / row_open[ba] / current_bank`。
    - 坑 4（防御性修复，已保留）：`ysyxSoC/perip/sdram/core_sdram_axi4/sdram_axi_core.v` 原先只锁存了 `write_data/write_dqm`，没有锁存请求地址；`ACTIVATE / PRECHARGE / READ / WRITE` 这些过程态直接读裸 `ram_addr_w`，理论上可能受上游未 accept 请求切换影响。修复：新增 `req_addr_latch_q`，过程态统一使用锁存地址驱动 `addr_q / bank_q`，并同步用于调试打印。
    - 调试踩坑：
      - 一开始把 `ACTIVE/PRECHARGE` 全量日志打开后，输出被 bank2 行切换刷屏，真正的首个错误点反而被淹没。
      - 后来改成只保留 `WRITE / READ_CMD / READ_DAT / ACK`，并只跟踪 `0x1000/0x3000`（对应 local `0x800/0x1800`），问题才真正收敛。
      - 只看大范围失败现象很容易误判成“地址位域算错 / 超边界”；实际根因是 bank 状态机和多 bank ACT 行为不完整。
    - 当前验证结论：
      - 缩小双地址复现已通过：`0xa0003000` 现在稳定写到 `local=0x00001800`，不再覆盖 `0x00000800`。
      - 已把 `sdram-mem-test` 改成大范围 32-bit 全扫版，当前先验证到 `64KB` 区间写读全通过。
      - `1MB` 全扫版也已恢复过，但在 Verilator 下耗时较长，后续可继续挂长测；当前已知根因链路已经打通。
    - 经验总结：
      - SDRAM bank/row 状态不能只用“一个 current_bank”去近似，凡是 `READ/WRITE/PRECHARGE/ACTIVE` 命令都必须以当前命令携带的 `ba` 为准。
      - 行为模型若只覆盖“单 bank 打开 -> 同 bank 访问”的最简路径，很容易在更真实的多 bank 调度下暴露假 bug。
      - 调试内存控制器时，先缩成“两地址 + 一种数据宽度 + 精准日志”，比直接跑 1MB 全扫更容易定位根因。
34. 关于不对齐访存（unaligned access）的进一步结论：
    - 重新检查 AM 构建脚本后确认：外部 `abstract-machine/scripts/isa/riscv.mk` 默认带有 `-mstrict-align`，因此 C 编译器不会主动生成真正的 misaligned `lw/sw`，而是会退化成若干 `sb/lbu` 等安全访问序列。
    - `cpu-tests/unalign` 的反汇编也印证了这一点：源码虽然写的是 `*((volatile unsigned *)(buf + 3))`，但最终代码并不是直接发 misaligned `sw/lw`，而是拆成 4 次 `sb` + 4 次 `lbu` + 软件拼接。
    - 因此，`unalign` 通过并不能证明“SDRAM 路径已经支持真实的跨 32-bit 边界 misaligned 单拍访问”；它证明的是：
      - 编译器在 `-mstrict-align` 下规避了这类指令；
      - 当前 LSU / 总线 / SDRAM 路径的 byte-lane / `WSTRB` / `DQM` 机制是通的。
    - 当前 LSU 已经具备“同一个 32-bit word 内偏移访问”的基础处理：
      - 通过 `addr[1:0]` 计算 `offset`
      - 写路径把 `wdata` 左移到对应 byte lane
      - 写路径把 `wmask/strb` 左移到对应 byte lane
      - 读路径对返回的 32-bit 数据按 `offset` 右移后再做符号/零扩展
    - 这意味着：
      - **不跨 32-bit 边界** 的 byte/halfword 偏移访问，当前方案可以自然工作；
      - **跨 32-bit 边界** 的真实 misaligned word/halfword 访问，当前方案并不完整，不能只靠 SDRAM 控制器或颗粒模型来“自动修正”。
    - 核心职责划分：
      - SDRAM 控制器只应该处理“对齐后的一个 32-bit beat + byte mask(DQM/WSTRB)”；
      - 如果要支持真实 misaligned 访存，应该由 **LSU** 在总线前完成地址对齐、数据/掩码移位、必要时拆成两拍；
      - 如果不打算支持，则也应该由 **LSU/异常路径** 在源头直接判定 misaligned trap，而不是把问题留给 SDRAM 侧。
    - 更具体地说：
      - 对于 `sb/sh` 或未跨界的偏移写，LSU 只需生成对齐 word 对应的 `WSTRB + shifted_wdata`；
      - 对于 `sw @ addr[1:0] != 0` 或 `lh/sh` 跨界这类情况，如果想支持，就必须拆成两个对齐访问；
      - 如果不想支持，就应该在 LSU 检测 `offset + size > 4`（或更严格的 ISA misaligned 条件）后直接上报异常。
    - 这次 SDRAM 修复本身没有引入“通用 misaligned 单拍访存支持”；本次修复聚焦的是：
      - `READ/WRITE` 路径的 stale `current_bank`
      - `PRECHARGE` 关错 bank
      - `S_ACTIVE` 下遗漏 `CMD_ACTIVE` 导致多 bank ACT 被芯片模型吃掉
      - 控制器过程态地址锁存
35. 2026-03 针对“guest 打印后再 `io_read()` 卡住”的进一步定位与修复：
    - 现象复现：构造本地最小 `probe/rodata_probe.c` 后确认，单独执行 `io_read(AM_UART_CONFIG)` 可以正常 `ebreak`；单独打印一行后直接 `return 0` 也可以正常 `ebreak`；但“先打印一行，再执行 `io_read(AM_UART_CONFIG)`”会稳定卡死/超时。
    - 关键排除：
      - `rodata` 字节读本身是正确的。probe 实测能稳定打印 `B31 31 00 C02`，说明 `"11"` 的 3 个字节与终止符读取都正常。
      - 因而此前“`putstr/printf` 触发 rodata 读 bug”的判断不成立；真正触发点是“打印之后再走 AM 的 `io_read` 分发路径”。
    - 进一步实锤：
      - 原始 `am/src/riscv/ysyxsoc/ioe.c` 使用 `lut[128]` 函数指针表，`ioe_read/ioe_write` 通过 `((handler_t)lut[reg])(buf)` 分发。
      - 在这种实现下，probe 的“print -> io_read”路径会卡住；而改成 `switch(reg)` 直接分发后，同一个 probe 立即恢复并可正常 `ebreak`。
      - 反汇编显示：原实现会从 `.data` 中读取**绝对函数指针**再 `jr a5`；新实现虽然仍可能被编译器优化成 jump table，但其跳转表是**相对偏移**形式，不再依赖从 `lut[]` 中取出绝对代码地址。
    - 当前结论：
      - 根因更接近于“CPU/系统对 `.data` 中保存的绝对函数地址这一路径存在问题”或“该路径对地址布局非常敏感”，而不是 `printf` / `putstr` / `rodata` 本身。
      - 作为稳定修复，`ioe.c` 已去掉 `lut` 函数指针表，改为 `switch-case` 直接分发 `AM_UART_CONFIG / AM_UART_RX / AM_TIMER_* / AM_INPUT_* / AM_UART_TX`。
      - 这个修复与用户观察完全一致：此前“前面一旦多打几个字符，后面的 `has_uart/has_kbd` 像是失效”其实是 `io_read(...)` 没有正常返回；切到直接分发后，问题消失。
    - 同轮还补了一个独立问题：
      - `sim_soc/test_bench_soc.cpp` 之前没有把 `top->externalPins_uart_rx` 拉到空闲高电平，导致纯命令行 testbench 下 UART RX 可能漂空；现已补上 `top->externalPins_uart_rx = 1;`。
      - 该问题主要影响非 NVBoard 仿真与 DiffTest 稳定性；NVBoard 版 testbench 之前已经有这个初始化。

## 2026-03-08 `.data` 初始化错位根因（函数指针 / `printf` / `ioe` 异常的真正来源）

### 最终结论
- 之前一度怀疑是 CPU 的 `jalr`/load-use 冒险问题，但最终 probe 证明这不是根因。
- 真正的问题在 `am/src/riscv/ysyxsoc/linker.ld`：
  - `.rodata` 末尾只对齐到 4 字节；
  - `.data` 开头又对齐到 8 字节；
  - 当时链接结果会在 `.rodata` 和 `.data` 之间留下一个 **4 字节的 VMA 空洞**。
- SSBL 在 `am/src/riscv/ysyxsoc/start.S` 中采用的是**线性整段搬运**：从 `_sdram_lma` 一直拷到 `_data_end`，默认假设 SDRAM 运行镜像在 VMA/LMA 上是连续的。
- 由于这 4 字节空洞只存在于 VMA、不存在于 LMA，导致从 Flash 搬到 SDRAM 时，`.data` 整体向后错位了 4 字节。
- 结果就是：所有普通 `.data` 变量的初值都可能错位。函数指针表、静态状态表、配置表最容易中招。

### 这个 bug 如何解释之前的现象
- `ioe.c` 早期的 `lut[128]` 函数指针表位于普通 `.data`，因此其初值在运行时并不可靠。
- 这就解释了为什么：
  - 有时 `AM_UART_CONFIG` / `AM_INPUT_CONFIG` 分发异常；
  - 加一句 guest 侧 `printf` 后现象会变化；
  - 看起来像“函数指针被打印影响了”或者“`jalr` 跳错了”。
- 实际上，`printf` 只是改变了链接布局/镜像内容，使错位后的 `.data` 呈现出不同的坏相，不是它本身和函数指针“冲突”。

### 关键复现实验
- 使用 `probe/rodata_probe.c` 做了一个最小实验：
  - `static void *lut[128] = { [1] = local_cfg };`
  - 程序启动后先打印 `lut[1]`，再执行填表和间接调用。
- 在修复前，运行结果是：
  - `I -> 00000000`
  - `A -> a0000000`
  - `B/C -> 000000ee`
- 这说明：
  - `lut[1]` 在进入 `main()` 时就已经不是 ELF 里应有的 `0xa000000c`，而是错成了 0；
  - 随后初始化循环把它补成了 `local_fail`；
  - 间接调用自然只会落到 `local_fail`。
- 修复后同一 probe 输出恢复为：
  - `I -> a000000c`
  - `A -> a000000c`
  - `B/C/D -> 00000001`

### 修复方式
- 修改了 `am/src/riscv/ysyxsoc/linker.ld`：
  - 将 `.rodata` 末尾对齐从 `ALIGN(4)` 改为 `ALIGN(8)`；
  - 让 `.data` 前面的 8 字节对齐不再额外制造 VMA 空洞；
  - 同时把此前单独的“额外 data 元数据”组织到 `.data` 输出段中，避免再次出现“线性拷贝假设”和实际段布局不一致的问题。
- 修复后，`readelf -S` 可见 `.data` 直接从 `.rodata` 末尾连续开始，不再有额外的 `.data.extra` NOBITS 空洞插在两者之间。

### 排查过程回顾
1. 先怀疑 `ioe.c` 的函数指针分发表，临时改成 `switch-case`，现象稳定下来，但这只是绕开症状。
2. 随后怀疑 `jalr` 目标地址低位被错误清零。
3. 用 probe 继续缩小范围后发现：
   - 问题在 `main()` 一进入时就已经存在；
   - 还没执行间接调用，`lut[1]` 初值就错了。
4. 再结合 `readelf -S/-s` 观察 section 排布，最终定位到 `.rodata` 与 `.data` 之间的 4 字节 VMA 空洞。

### 当前状态
- 根因已确认并修复。
- `sim_soc/test_bench_soc.cpp` 里补的 `externalPins_uart_rx = 1;` 仍然保留，这个修复和本次 `.data` 问题独立，仍然是正确的。
- `am/src/riscv/ysyxsoc/ioe.c` 当前仍使用 `switch-case` 分发；即使现在 linker 已修好，这个实现本身也没有问题，可以继续保留。

### 22. VGA 帧缓冲 + NVBoard 显示链路
- `ysyxSoC/perip/vga/vga_top_apb.v` — 实现 APB VGA 控制器
  - 地址窗口仍为 `0x2100_0000 ~ 0x211f_ffff`，当前把前 `640 * 480 * 4 = 1,228,800B` 作为线性帧缓冲使用
  - APB 侧支持 32-bit 读写和 `WSTRB` 字节写掩码，像素格式按 `0x00RRGGBB` 输出到 `vga_r/g/b`
  - 显示侧实现 640x480 扫描时序：`hsync`/`vsync` 按 NVBoard 约定时序输出，`vga_valid` 在可视区拉高，并按光栅顺序持续读取帧缓冲
  - 当前实现使用 SoC 内部 `reg [31:0] fb_mem[]` 作为简化帧缓冲，优先满足仿真/NVBoard 显示需求
- `sim_soc/constr/ysyxSoCFull.nxdc` — 新增 VGA 绑线
  - `externalPins_vga_hsync/vsync/valid` 分别绑定到 `VGA_HSYNC/VGA_VSYNC/VGA_BLANK_N`
  - `externalPins_vga_r/g/b[7:0]` 绑定到 NVBoard 的 24 根 VGA 颜色引脚
  - `sim_soc/build/auto_bind.cpp` 可由 `make -C sim_soc sim-nvboard` 自动重新生成并包含上述 VGA 绑定
- `am/src/riscv/ysyxsoc/ioe.c` — 补齐 AM GPU 基本抽象
  - `AM_GPU_CONFIG` 返回 `640x480`、`present=true`、`has_accel=false`
  - `AM_GPU_STATUS` 恒返回 ready
  - `AM_GPU_FBDRAW` 直接把像素块写入 `0x2100_0000` 帧缓冲，并在软件侧处理越界裁剪
  - 由于 NVBoard 端自动按 VGA 时序刷新，当前 `sync=true` 只作为兼容参数保留，不需要额外软件 flush 寄存器
- 验证
  - `make -C sim_soc sim-nvboard` 可通过，说明 VGA RTL + NVBoard 自动绑线 + 顶层端口连接均已编译打通
  - `make ARCH=riscv32im-ysyxsoc mainargs=v insert-arg`（`am-tests`）可重新编译 `video` 用例，随后 `SDL_VIDEODRIVER=dummy CCACHE_DISABLE=1 make -C sim_soc USE_NVBOARD=1 run IMG=... LIMIT_CYCLE=6000000` 可稳定启动并运行到超时，未出现新的编译/链接/启动错误
### Clangd修复
- 现在仓库根目录已经生成了 compile_commands.json，里面有 48 条真实编译命令。                                                                       
- 我确认它已经覆盖了你关心的 am 平台文件，比如 am/src/riscv/ysyxsoc/ioe.c、am/src/riscv/ysyxsoc/trm.c，也覆盖了 sim_soc/test_bench_soc_nvboard.cpp。
- 我还加了 .clangd 和一键脚本，后面你不用手搓一长串 bear + make。                                                                                  
                                                                                                                                                   
依赖关系                                                                                                                                           
                                                                                                                                                   
- am-tests 的入口很薄，/home/lj/ysyx-workbench/am-kernels/tests/am-tests/Makefile:1 只是定义 SRCS，然后直接 include $(AM_HOME)/Makefile。          
- 真正的编译规则在 /home/lj/ysyx-workbench/abstract-machine/Makefile:1：这里决定了 CC/CFLAGS/INC_PATH，并递归构建 am、klib。                       
- ARCH=riscv32im-ysyxsoc 会走 am/scripts/riscv32im-ysyxsoc.mk:1，再继续包含 am/scripts/platform/ysyxsoc.mk:1。                                     
- am/scripts/platform/ysyxsoc.mk:1 才真正把 am/src/riscv/ysyxsoc/start.S、trm.c、cte.c、trap.S、ioe.c、timer.c、input.c 这些平台源文件拉进来。     
                                                                                                                                                   
我做的事                                                                                                                                           
                                                                                                                                                   
- 新增 .clangd，固定 clangd 从仓库根找数据库：.clangd:1                                                                                            
- 新增一键脚本 tools/gen_compile_commands.sh:1                                                                                                     
    - 清理并重建 sim_soc                                                                                                                           
    - 清理并重建 abstract-machine/am                                                                                                               
    - 清理并重建 abstract-machine/klib                                                                                                             
    - 重建 am-tests                                                                                                                                
    - 用 bear 统一追加到根目录 compile_commands.json                                                                                               
    - 最后按真实路径去重，避免 symlink/重复项干扰 clangd                                                                                           
- 放开了 gitignore 让 .clangd 和脚本可跟踪：.gitignore:1                                                                                           
                                                                                                                                                   
以后怎么用                                                                                                                                         
- 全量重建索引库：
    - tools/gen_compile_commands.sh
- 快速增量更新：
    - CLEAN_BUILD=0 tools/gen_compile_commands.sh
- 生成后如果编辑器还报红：
    - 重启 clangd
    - 或重新打开工作区根目录 /home/lj/ysyx-workbench/Graduation-project

这类多目录工作区，clangd 最稳的做法

- 永远只维护一个“根” compile_commands.json
- 这个数据库里的 file 最好是绝对路径
- 所有跨目录构建都汇总到这一个数据库
- 尽量从“仓库真实路径”打开文件，不要从外部 symlink 路径打开
    - 例如优先打开 am/src/riscv/ysyxsoc/ioe.c
    - 不要优先打开 /home/lj/ysyx-workbench/abstract-machine/am/src/riscv/ysyxsoc/ioe.c
- 每次你改了构建参数、ARCH、头文件路径、切换 target 后，都重跑一次脚本
