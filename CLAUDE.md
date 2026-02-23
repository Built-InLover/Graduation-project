# NPC 接入 ysyxSoC 改造进度

## 项目概述
将自研 RISC-V 5 级流水线处理器（DistributedCore）接入 ysyxSoC，替换原有的虚拟 SRAM/UART，使用 ysyxSoC 提供的真实外设。

## 目录结构
- `playground/src/` — Chisel 源码（CPU 核心）
- `build/ysyx_23060000.sv` — 生成的 CPU Verilog（需 sed 修正命名）
- `sim_soc/` — 接入 ysyxSoC 的仿真环境（Makefile + test_bench_soc.cpp）
- `/home/lj/ysyx-workbench/ysyxSoC/` — ysyxSoC 环境
- `/home/lj/ysyx-workbench/ysyxSoC/build/ysyxSoCFull.v` — SoC 顶层（已替换 ysyx_00000000 → ysyx_23060000）
- `/home/lj/ysyx-workbench/mycore/` — 旧的独立仿真环境（使用 DPI-C 虚拟内存，不再使用）

## 当前状态：AM 运行时 + cpu-tests/dummy、fib 通过

CPU 从 MROM 取指，数据/栈在 SRAM，通过 AXI4 总线访问 UART。AM 平台 `riscv32im-ysyxsoc` 已创建。dummy 81 周期 ebreak 退出，fib（含 .data 搬运）3009 周期正常完成。

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
  - char-test.bin 编译目标：riscv32i 交叉编译 + objcopy，链接地址 0x20000000
- `sim_soc/test_bench_soc.cpp` — 仿真驱动
  - mrom_read 加载 char-test.bin 到 4KB 缓冲区，flash_read 桩函数（assert(0)）
  - DPI-C sim_ebreak() 终止机制：CSR 检测到 ebreak 后通知 testbench 退出
  - 复位 10 周期后主循环（最多 100 万周期，ebreak 提前退出）
  - FST 波形输出到 obj_dir/ysyxSoCFull.fst
- `sim_soc/char-test.c` — 最简 UART 测试：直接写 'A' 到 UART THR (0x10000000)
- `sim_soc/mrom.ld` — 链接脚本，起始地址 0x20000000

### 7. AM 运行时环境（riscv32im-ysyxsoc）
- `abstract-machine/scripts/riscv32im-ysyxsoc.mk` — ARCH 入口（RV32IM + libgcc）
- `abstract-machine/scripts/platform/ysyxsoc.mk` — 平台配置（最小 TRM，无 IOE/CTE）
- `abstract-machine/am/src/riscv/ysyxsoc/linker.ld` — 分离式链接脚本
  - MROM (0x20000000, 4KB): .text + .rodata
  - SRAM (0x0f000000, 8KB): .data + .bss + 栈(4KB) + 堆
  - .data 使用 `AT > MROM` 实现 LMA/VMA 分离
- `abstract-machine/am/src/riscv/ysyxsoc/start.S` — 启动代码（设 sp 到 SRAM）
- `abstract-machine/am/src/riscv/ysyxsoc/trm.c` — TRM 运行时
  - putch() 写 UART 0x10000000（sb 指令）
  - halt() 通过 ebreak 退出
  - 无 mainargs 机制（简化）
- sim_soc 支持 `IMG=` 参数指定 bin 文件路径

### 8. ysyxSoCFull.v 模块名替换
- `/home/lj/ysyx-workbench/ysyxSoC/build/ysyxSoCFull.v` 第 1465 行
- `ysyx_00000000` → `ysyx_23060000`（已用 sed 完成）

## 编译与运行
```bash
# 生成 Verilog（含 sed 修正）
cd sim_soc && make verilog

# 编译 AM 测试程序
cd /home/lj/ysyx-workbench/am-kernels/tests/cpu-tests
make ARCH=riscv32im-ysyxsoc ALL=dummy

# verilator 编译 + 运行仿真
cd sim_soc && make sim
cd sim_soc && make run IMG=/path/to/test.bin

# 旧的 char-test 仍可用
cd sim_soc && make char-test.bin && make run
```

## 下一步待办
1. 实现 flash_read（让 CPU 能从 flash 取到指令，加载更大程序）
2. 跑通更多 cpu-tests
3. 恢复 difftest 功能（debug 信号需要通过层级路径访问）

## 已清理的旧文件（已删除，可通过 git 历史恢复）
- `common/AXI4Lite.scala`、`common/SimpleBus.scala` — 旧总线协议
- `core/SoCTop.scala`、`core/RAM.scala`、`core/Axi4LiteUART.scala`、`core/Axi4LiteCLINT.scala` — 旧 SoC 路由和 DPI-C 虚拟外设
- `top/main.scala` — 旧入口点（已被 main_ysyxsoc.scala 替代）
