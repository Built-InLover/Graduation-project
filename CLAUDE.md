# NPC 接入 ysyxSoC 改造进度

## 项目概述
将自研 RISC-V 5 级流水线处理器（DistributedCore）接入 ysyxSoC，替换原有的虚拟 SRAM/UART，使用 ysyxSoC 提供的真实外设。

## 目录结构
- `playground/src/` — Chisel 源码（CPU 核心）
- `build/ysyx_23060000.sv` — 生成的 CPU Verilog（需 sed 修正命名）
- `sim_soc/` — 接入 ysyxSoC 的仿真环境（Makefile + test_bench_soc.cpp）
- `am/` — AM ysyxsoc 平台文件（源文件在此，abstract-machine 对应位置为软链接）
  - `am/scripts/riscv32im-ysyxsoc.mk` — ARCH 入口
  - `am/scripts/platform/ysyxsoc.mk` — 平台配置
  - `am/src/riscv/ysyxsoc/start.S` — 启动代码
  - `am/src/riscv/ysyxsoc/trm.c` — TRM 运行时
  - `am/src/riscv/ysyxsoc/linker.ld` — 链接脚本
- `/home/lj/ysyx-workbench/ysyxSoC/` — ysyxSoC 环境
- `/home/lj/ysyx-workbench/ysyxSoC/build/ysyxSoCFull.v` — SoC 顶层（已替换 ysyx_00000000 → ysyx_23060000）
- `/home/lj/ysyx-workbench/mycore/` — 旧的独立仿真环境（使用 DPI-C 虚拟内存，不再使用）

## 当前状态：XIP Flash 执行

在真实 SPI 协议基础上实现了 XIP（Execute In Place）硬件状态机。CPU 访问 0x30000000 地址空间时，spi_top_apb.v 内部状态机自动完成 SPI 传输（8 states: IDLE→WR_TX1→WR_TX0→WR_SS→WR_CTRL→POLL→RD_RX0→DONE），对 CPU 透明。xip-flash-test 256 word 校验通过，xip-jump 从 MROM 跳转到 flash 执行 char-test-flash 输出 'A\n' + ebreak 成功。软件驱动 SPI 和 XIP 硬件状态机共存：SPI 寄存器访问（0x10001000）走 APB 直连，flash 地址访问（0x30000000）走 XIP 状态机。

## 开发规则
- **文档同步**：每项任务完成后，必须及时更新 CLAUDE.md（当前状态、已完成工作、开发历程等相关章节）
- **Git 提交**：commit 后顺手 push
- **linker.ld 内存布局注释**：每次修改 linker.ld 时，同步更新文件顶部的 ASCII 内存布局图

## 已完成的工作

### 1. AXI4 接口改造（全链路原生 AXI4）
- `common/AXI4.scala` — 完整 AXI4 接口定义（id/len/size/burst/last）
- `corewithbus/IFU.scala` — AR 通道 id=0, len=0, size=2, burst=1, 复位 PC=0x20000000（MROM）
  - inst_queue（Queue, 深度4）缓冲 R 通道响应，r.ready 不依赖下游流水线，防止死锁
- `corewithbus/LSU.scala` — AR/AW 通道 id=1, size 根据 func 动态设置（lb=0, lh=1, lw=2）

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
  - xip-jump.bin / char-test-flash.bin 编译目标
- `sim_soc/test_bench_soc.cpp` — 仿真驱动
  - mrom_read 加载 bin 到 4KB 缓冲区，flash_read 读取 16MB flash 缓冲区（已知模式初始化）
  - DPI-C sim_ebreak() 终止机制：CSR 检测到 ebreak 后通知 testbench 退出
  - 复位 10 周期后主循环（最多 100 万周期，ebreak 提前退出）
  - FST 波形输出到 obj_dir/ysyxSoCFull.fst
- `sim_soc/xip-jump.c` — MROM 程序，跳转到 0x30000000（XIP 执行入口）
- `sim_soc/char-test-flash.c` — Flash 版 char-test：输出 'A\n' + ebreak，链接到 0x30000000
- `sim_soc/mrom.ld` — MROM 链接脚本（起始 0x20000000）
- `sim_soc/flash.ld` — Flash 链接脚本（起始 0x30000000）

### 7. AM 运行时环境（riscv32im-ysyxsoc）
- 源文件在 `am/` 目录下，`abstract-machine/` 对应位置为软链接（绝对路径）
- `am/scripts/riscv32im-ysyxsoc.mk` — ARCH 入口（RV32IM + libgcc）
- `am/scripts/platform/ysyxsoc.mk` — 平台配置（最小 TRM，无 IOE/CTE）
- `am/src/riscv/ysyxsoc/linker.ld` — 分离式链接脚本
  - MROM (0x20000000, 4KB): .text + .rodata
  - SRAM (0x0f000000, 8KB): .data + .bss + 栈(4KB) + 堆
  - .data 使用 `AT > MROM` 实现 LMA/VMA 分离
- `am/src/riscv/ysyxsoc/start.S` — 启动代码（设 sp 到 SRAM）
- `am/src/riscv/ysyxsoc/trm.c` — TRM 运行时
  - putch() 写 UART 0x10000000（sb 指令）
  - halt() 通过 ebreak 退出
  - 无 mainargs 机制（简化）
- sim_soc 支持 `IMG=` 参数指定 bin 文件路径
- **软链接约定**：后续新增 AM ysyxsoc 相关文件，先在 `am/` 下创建，再去 `abstract-machine/` 对应位置加软链接（绝对路径）

### 8. ysyxSoCFull.v 模块名替换
- `/home/lj/ysyx-workbench/ysyxSoC/build/ysyxSoCFull.v` 第 1465 行
- `ysyx_00000000` → `ysyx_23060000`（已用 sed 完成）

### 9. DiffTest（DPI-C 方案）
- `core/SimDebug.scala` — SimDifftest BlackBox，内联 Verilog 调用 sim_set_gpr/sim_difftest
- `top/top.scala` — DistributedCore 中实例化 SimDifftest，连接 debug_regs/debug_csr
- `sim_soc/test_bench_soc.cpp` — DiffTest 逻辑（`#ifdef DIFFTEST_ON`）
  - CPU_state 结构体与 NEMU 一致（注意 CSR 字段顺序：mtvec, mepc, mstatus, mcause）
  - NPC debug_csr 顺序：[0]=mcause, [1]=mepc, [2]=mstatus, [3]=mtvec
  - DPI-C: sim_set_gpr() 设置 GPR, sim_difftest() 提交指令
  - init_difftest(): dlopen NEMU .so → difftest_init → memcpy MROM → regcpy 同步初始状态
  - 主循环每拍检查 difftest_commit，比较 GPR/PC/CSR
- `sim_soc/Makefile` — DIFFTEST=1 启用，LDFLAGS 加 -ldl，run 目标接受 DIFF 参数
- NEMU 配置：`.config` 中 `CONFIG_TARGET_SHARE=y`（非 NATIVE_ELF），编译产物为 .so
  - `nemu/src/memory/paddr.c` — TARGET_SHARE 下添加 MROM(0x20000000,4KB) + SRAM(0x0f000000,8KB)
  - `nemu/src/isa/riscv32/init.c` — TARGET_SHARE 下 PC=0x20000000，跳过内置镜像

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
- `sim_soc/xip-jump.c` — MROM 程序，跳转到 0x30000000（IFU 后续取指全走 XIP）
- `sim_soc/char-test-flash.c` — Flash 版 char-test：输出 'A\n' + ebreak，链接到 0x30000000
- `sim_soc/flash.ld` — Flash 链接脚本（起始 0x30000000）
- `sim_soc/Makefile` — 新增 xip-jump.bin、char-test-flash.bin 编译目标

## 编译与运行
```bash
# 生成 Verilog（含 sed 修正）
cd sim_soc && make verilog

# 编译 AM 测试程序
cd /home/lj/ysyx-workbench/am-kernels/tests/cpu-tests
make ARCH=riscv32im-ysyxsoc ALL=dummy

# verilator 编译 + 运行仿真（不带 DiffTest）
cd sim_soc && make sim
cd sim_soc && make run IMG=/path/to/test.bin

# 带 DiffTest 运行
cd sim_soc && make run DIFFTEST=1 IMG=/path/to/test.bin DIFF=/home/lj/ysyx-workbench/nemu/build/riscv32-nemu-interpreter-so

# 编译 NEMU .so（需要 CONFIG_TARGET_SHARE=y）
cd nemu && make ISA=riscv32 -j$(nproc)

# bitrev 测试（无 DiffTest）
make run IMG=.../bitrev-test-riscv32im-ysyxsoc.bin

# SPI flash 读取测试（无 DiffTest）
make run IMG=.../spi-flash-test-riscv32im-ysyxsoc.bin

# XIP 读取测试
make ARCH=riscv32im-ysyxsoc ALL=xip-flash-test
cd sim_soc && make run IMG=.../xip-flash-test-riscv32im-ysyxsoc.bin

# XIP 执行测试（MROM 跳转到 flash 执行）
cd sim_soc && make xip-jump.bin char-test-flash.bin
make run IMG=xip-jump.bin FLASH=char-test-flash.bin

# cpu-tests DiffTest 回归（需临时开 FAST_FLASH）
# 编辑 spi_top_apb.v 取消注释 `define FAST_FLASH，重新 make sim
```

## 下一步待办
1. 将 AM 程序（cpu-tests）改为从 flash 启动（XIP + AM linker 适配）
2. SPI/XIP 相关测试的 DiffTest 支持（NEMU 侧模拟 SPI 行为或 flash 地址映射）

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

## 已清理的旧文件（已删除，可通过 git 历史恢复）
- `common/AXI4Lite.scala`、`common/SimpleBus.scala` — 旧总线协议
- `core/SoCTop.scala`、`core/RAM.scala`、`core/Axi4LiteUART.scala`、`core/Axi4LiteCLINT.scala` — 旧 SoC 路由和 DPI-C 虚拟外设
- `top/main.scala` — 旧入口点（已被 main_ysyxsoc.scala 替代）
- `sim_soc/char-test.c`、`sim_soc/char-test-sram.c`、`sim_soc/flash-loader.c`、`sim_soc/sram.ld` — 被 XIP 方案取代（xip-jump + char-test-flash）
