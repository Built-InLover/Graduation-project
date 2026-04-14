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

GPIO 已开始接入：`0x1000_2000` 的低 16 位寄存器可直接驱动 `externalPins_gpio_out[15:0]`。`sim_soc` 现提供两套仿真入口：普通命令走无 GUI 的 `test_bench_soc.cpp`；NVBoard 命令走 `test_bench_soc_nvboard.cpp`，并通过 `sim_soc/constr/ysyxSoCFull.nxdc` 将 `externalPins_gpio_out[15:0]` 绑定到 16 个 LED、`externalPins_gpio_in[15:0]` 绑定到 16 个拨码开关。
`riscv32im-ysyxsoc` 平台现已补齐 AM 的 UART 抽象（`AM_UART_CONFIG`/`AM_UART_TX`/`AM_UART_RX`），并支持和 `npc` 类似的 `mainargs` 注入流程。

键盘输入链路已接通：PS/2 接收器使用 start-bit 驱动状态机，`0x10011000` 读扫描码、无数据返回 `0`。`input.c` 在软件侧翻译为 AM 的 `AM_INPUT_KEYBRD` 事件。

PSRAM 已实现 QPI 模式：控制器复位后先用 1-bit SPI 发 35h 切换颗粒到 QPI，之后 CMD/ADDR/DATA 全部 4-bit。

NEMU（TARGET_SHARE 模式）已彻底去掉 pmem，改为独立地址空间：mrom_data(4KB) + sram_data(8KB) + flash_data(16MB) + psram_data(4MB)，guest_to_host 按地址分派到对应数组。

**RT-Thread 仿真状态**：RT-Thread 内核 ~185KB bin 编译通过，但 verilator 仿真未能验证——从 Flash XIP 搬运 185KB 到 PSRAM 所需仿真周期过多，实际运行不可行。RT-Thread 移植的正确性目前不可知，需要后续通过其他手段（如上板或优化仿真速度）验证。

## 开发规则
- **文档同步**：每项任务完成后，必须及时更新 CLAUDE.md（当前状态、已完成工作等相关章节）
- **Git 提交**：commit 后顺手 push
- **linker.ld 内存布局注释**：每次修改 linker.ld 时，同步更新文件顶部的 ASCII 内存布局图
- **软链接约定**：后续新增 AM ysyxsoc 相关文件，先在 `am/` 下创建，再去 `abstract-machine/` 对应位置加软链接（绝对路径）
- **host vs guest 日志**：`sim_soc/*.cpp` / Verilog `$display` 是 host 侧日志；`am/` / `klib` 中的 `printf` 是 guest 侧输出（走 DUT UART），会改变时序，调试 I/O 链路时优先用 host 侧日志

## 已完成的工作

### 1. AXI4 接口改造（全链路原生 AXI4）
- `common/AXI4.scala` — 完整 AXI4 接口定义（id/len/size/burst/last）
- `corewithbus/IFU.scala` — AR 通道 id=0, len=0, size=2, burst=1, 复位 PC=0x30000000（Flash XIP）
  - inst_queue（Queue, 深度4）缓冲 R 通道响应，r.ready 不依赖下游流水线，防止死锁
- `corewithbus/LSU.scala` — AR/AW 通道 id=1, size 根据 func 动态设置（lb=0, lh=1, lw=2）

### 1.1 第一版 ICache（IFU 前端缓存）
- `corewithbus/ICache.scala` — 可配置 blocking icache
  - 参数：`ICacheConfig(addrBits, dataBits, nSets, nWays, lineBytes, cacheableRegions)`
  - 当前默认值：`nSets=64`，`nWays=4`，`lineBytes=4`（总容量 1KB）
  - cacheable 判定：缓存 `0x3000_0000`（Flash XIP）和 `0xA000_0000`（SDRAM execute）；其余走 bypass

### 1.2 构建环境补齐（mill launcher + JDK）
- 仓库根目录新增 Unix 版 `mill` launcher 与兼容入口 `millw`

### 2. CLINT（保留在 CPU 内部）
- `core/Axi4CLINT.scala` — AXI4 接口，地址范围 0x0200_0000~0x0200_ffff
- mtime 低32位 = 0x0200_BFF8，高32位 = 0x0200_BFFC

### 2.5 ebreak DPI-C 终止机制
- `core/CSR.scala` — SimEbreak BlackBox，ebreak 时调用 DPI-C sim_ebreak()

### 3. DistributedCore
- `top/top.scala` — 直接暴露 ifu_bus 和 lsu_bus 两个 AXI4Interface IO 端口

### 4. ysyx_23060000 顶层模块
- `top/ysyx_23060000.scala` — 符合 cpu-interface.md 规范
- 内部：DistributedCore + AXI4CLINT
- LSU 总线路由：CLINT 地址(高16位==0x0200)走内部，其余走外部 master
- IFU 和 LSU(非CLINT) 仲裁共享一个 AXI4 Master（LSU 优先）
- R 通道路由：根据 r.bits.id 区分（IFU id=0, LSU id=1）

### 5. Verilog 生成 + sed 修正
已集成到 `sim_soc/Makefile` 的 `verilog` 目标，`sim`/`run` 会在 Scala 源更新时自动 regenerate。

### 6. 仿真环境（sim_soc/）
- `sim_soc/Makefile` — verilator 编译，顶层 ysyxSoCFull，含 --timescale --no-timing --trace-fst --autoflush
- `sim_soc/test_bench_soc.cpp` — 仿真驱动（argv[1]=bin, argv[2]=diff_so）
  - flash_read/mrom_read DPI-C，sim_ebreak() 终止机制
  - 复位 10 周期后主循环，FST 波形输出到 obj_dir/ysyxSoCFull.fst

### 7. AM 运行时环境（riscv32im-ysyxsoc）
- 源文件在 `am/` 目录下，`abstract-machine/` 对应位置为软链接（绝对路径）
- `am/src/riscv/ysyxsoc/linker.ld` — 二级 Bootloader 链接脚本
  - FLASH (0x30000000, 16M): fsbl(VMA=LMA) + .ssbl/.text/.rodata/.data LMA
  - SRAM (0x0f000000, 8K): .ssbl VMA
  - PSRAM (0x80000000, 4M): .text/.rodata/.data VMA + .bss + heap（_bss_end~0x80400000）
- `am/src/riscv/ysyxsoc/start.S` — 二级 Bootloader（FSBL→SSBL→_trm_init）
- `am/src/riscv/ysyxsoc/trm.c` — putch() 写 UART 0x10000000，halt() 通过 ebreak 退出，支持 mainargs

### 8. ysyxSoCFull.v 模块名替换
- `ysyx_00000000` → `ysyx_23060000`

### 9. DiffTest（DPI-C 方案）
- `core/SimDebug.scala` — SimDifftest BlackBox
- `sim_soc/test_bench_soc.cpp` — DiffTest 逻辑（`#ifdef DIFFTEST_ON`）
  - CPU_state CSR 字段顺序：mtvec, mepc, mstatus, mcause
  - NPC debug_csr 顺序：[0]=mcause, [1]=mepc, [2]=mstatus, [3]=mtvec
- NEMU 配置：`CONFIG_TARGET_SHARE=y`，独立地址空间（无 pmem）

### 10. 异常处理机制（统一 WBU commit 点）
- 架构：IFU(fault) → IDU(exception) → EXU(透传) → WBU(检测) → CSR(exc_in注入) → mtvec redirect + flush_all
- CauseCode: INST_ACCESS_FAULT(1), LOAD_ACCESS_FAULT(5), STORE_ACCESS_FAULT(7)

### 11~13. mem-test / UART16550 / Flash 读取
- SRAM/PSRAM 8/16/32 位访存校验通过
- uart_init() 设 8N1/divisor=1，putch() 轮询 LSR THRE
- Flash 已改用真实 SPI 协议（非 FAST_FLASH）

### 14. bitrev SPI slave（纯测试练手模块）
- `ysyxSoC/perip/bitrev/bitrev.v` — 位翻转 SPI slave，SoC 中连接在 SPI SS[7]

### 15~18. SPI Flash / XIP / Flash 直接启动
- XIP 状态机（8 states）在 `spi_top_apb.v`
- IFU 复位 PC = 0x30000000，CPU 复位后直接从 Flash XIP 取指

### 19. PSRAM 颗粒仿真模型 + QPI 模式
- `ysyxSoC/perip/psram/psram.v` — QSPI/QPI 颗粒行为模型（4MB，DPI-C 稀疏存储）
- `ysyxSoC/perip/psram/efabless/EF_PSRAM_CTRL.v` — QPI 版（PSRAM_INIT 发 35h）
- `ysyxSoC/perip/psram/efabless/EF_PSRAM_CTRL_wb.v` — 主 FSM 加 ST_INIT 状态

### 20. NEMU 独立地址空间改造（去 pmem）
- TARGET_SHARE 下：mrom_data(4KB) + sram_data(8KB) + flash_data(16MB) + psram_data(4MB)
- UART 读返回 LSR=0x60，SPI 读返回 0，CLINT 读返回 0

### 21. RT-Thread 移植（CTE + linker.ld 合并 + CLINT 映射）
- CTE 支持：cte.c + trap.S，M-mode 异常处理（ecall yield/timer IRQ）
- linker.ld 合并 RT-Thread extra.ld：.data.extra + .bss.extra
- 栈从 SRAM 移到 PSRAM 末尾 32KB

### 22. Bootloader 诊断输出（汇编 UART）
- start.S 各阶段输出 F/S/M 字符

### 23~24. SDRAM 颗粒仿真模型 + 位扩展（双颗粒 32-bit）
- `sdram.v` — MT48LC16M16A2 行为模型，CHIP_SEL 参数（lo/hi 独立 DPI-C）
- `sdram_axi_core.v` — 数据总线 32-bit，DQM 4-bit，BL=1
- `ysyxSoCFull.v` — 双颗粒实例化（sdram_lo + sdram_hi）
- SDRAM 地址空间：0xa0000000~0xbfffffff

### 25. VGA 帧缓冲 + NVBoard 显示链路
- `ysyxSoC/perip/vga/vga_top_apb.v` — APB VGA 控制器，640x480 线性帧缓冲（0x2100_0000）
- `ioe.c` — AM GPU 抽象：AM_GPU_CONFIG(640x480) / AM_GPU_FBDRAW

### 26. `.data` 初始化错位修复
- 根因：linker.ld 中 `.rodata` 末尾 ALIGN(4) 与 `.data` 开头 ALIGN(8) 之间产生 4 字节 VMA 空洞，SSBL 线性搬运导致 `.data` 整体错位
- 修复：`.rodata` 末尾改为 ALIGN(8)，消除空洞
- `ioe.c` 改为 switch-case 分发（不再用函数指针表），保留此实现

### 27. 性能计数器（DPI-C BlackBox）
- `core/SimDebug.scala` — SimPerfCounters BlackBox，每周期调用 `sim_perf_event()` DPI-C
- `corewithbus/ICache.scala` — 新增 `perf_hit`/`perf_miss` 输出（仅 cacheable 访问）
- `corewithbus/IFU.scala` — 透传 ICache perf 信号
- `top/top.scala` — `user_mode` 锁存 + `futype_q`（镜像 order_q）+ SimPerfCounters 实例化
- `sim_soc/test_bench_soc.cpp` / `test_bench_soc_nvboard.cpp` — C++ 侧累计计数，ebreak 时打印报告
- 统计内容：
  - Cycles（total/user）、IFU fetch、IDU dispatch、Fetch waste%、IPC(user)
  - ICache hit/miss（total/user 两套）
  - 指令分类（ALU/BRU/LSU/MDU/CSR）commit 数量、占比、avg CPI
- user_mode 区分 bootloader（Flash/SRAM）和用户程序（SDRAM），排除搬运阶段的统计干扰
- FuType 通过 futype_q 从 IDU dispatch 传递到 WBU commit 点

### Clangd 配置
- 仓库根目录 `compile_commands.json`（48 条编译命令）
- `.clangd` 配置文件 + `tools/gen_compile_commands.sh` 一键脚本
- 全量重建：`tools/gen_compile_commands.sh`
- 增量更新：`CLEAN_BUILD=0 tools/gen_compile_commands.sh`

## 已清理的旧文件（已删除，可通过 git 历史恢复）
- `common/AXI4Lite.scala`、`common/SimpleBus.scala` — 旧总线协议
- `core/SoCTop.scala`、`core/RAM.scala`、`core/Axi4LiteUART.scala`、`core/Axi4LiteCLINT.scala` — 旧 SoC 路由和 DPI-C 虚拟外设
- `top/main.scala` — 旧入口点（已被 main_ysyxsoc.scala 替代）
- `sim_soc/char-test.c`、`sim_soc/char-test-sram.c`、`sim_soc/flash-loader.c`、`sim_soc/sram.ld` — 被 XIP 方案取代
- `sim_soc/xip-jump.c`、`sim_soc/char-test-flash.c`、`sim_soc/flash.ld`、`sim_soc/mrom.ld` — 被 Flash 直接启动取代

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
