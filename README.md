# NPC 接入 ysyxSoC — 开发日志与调试记录

> 项目技术参考文档请见 [CLAUDE.md](./CLAUDE.md)

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

## 调试记录

### SDRAM 多 bank / 多行调试复盘（#33）

调试背景：为避免"程序本身搬到 SDRAM 后又覆盖测试区"影响结论，切回"程序运行在 PSRAM，单独用 `sdram-mem-test` 读写 SDRAM"的模式排查。

缩点策略：先把 `sdram-mem-test` 从大范围扫描缩到 16KB、1KB，最终缩到只盯 `0xa0001000` 与 `0xa0003000` 两个地址；再加前缀写 / 前缀读扫描，确认问题不是简单边界越界，而是与 bank/row 状态切换有关。

现象：
- `0xa0003000` 的 32-bit 模式值 `0x9e8c8ddf` 会出现在 `0xa0001000`
- 控制器日志显示 `cpu=a0003000 -> row=1 bank=2 col=0`，但芯片日志实际写入到了 `local=0x00000800 (row=0 bank=2 col=0)`

修复的四个坑：
1. `sdram.v` 在 `S_ACTIVE` 下处理 `CMD_READ/CMD_WRITE` 时没有同步更新 `current_bank`
2. `sdram.v` 在 `S_ACTIVE` 下处理 `CMD_PRECHARGE` 时错误地关闭了 `row_open[current_bank]` 而非 `row_open[ba]`
3. **核心根因**：SDRAM 芯片模型在 `S_ACTIVE` 状态下不处理新的 `CMD_ACTIVE`，真实 SDRAM 允许多 bank 同时打开
4. `sdram_axi_core.v` 未锁存请求地址，过程态直接读裸 `ram_addr_w`

经验总结：
- SDRAM bank/row 状态不能只用"一个 current_bank"去近似，凡是 `READ/WRITE/PRECHARGE/ACTIVE` 命令都必须以当前命令携带的 `ba` 为准
- 调试内存控制器时，先缩成"两地址 + 一种数据宽度 + 精准日志"，比直接跑 1MB 全扫更容易定位根因

### 不对齐访存（unaligned access）结论（#34）

- AM 构建脚本默认带 `-mstrict-align`，C 编译器不会生成真正的 misaligned `lw/sw`，而是退化成 `sb/lbu` 序列
- `cpu-tests/unalign` 通过只证明编译器规避了 misaligned 指令 + byte-lane/WSTRB/DQM 机制是通的
- 当前 LSU 支持同一 32-bit word 内的偏移访问（addr[1:0] 移位），但不支持跨 32-bit 边界的真实 misaligned 访存
- 核心职责划分：SDRAM 控制器只处理对齐 32-bit beat + byte mask；misaligned 拆分或 trap 应由 LSU 负责

### PS/2 键盘调试记录

根因：`ps2_top_apb.v` 接收逻辑在检测到任意下降沿后就直接开始按 11 位盲收，未先等待合法的 start bit，帧边界容易错位。

修复：接收器改为显式的 start-bit 驱动状态机：空闲时仅在采样边沿看到 `ps2_data==0` 才进入接收态，然后依次接收 data/parity/stop，只在 `start=0 && stop=1 && odd parity` 全部成立时将扫描码压入 FIFO。

排查过程：
- 先确认 NVBoard 绑定与焦点分流正确（UART 焦点输入走 UART RX，非 UART 焦点输入走 PS/2）
- 通过临时日志分别验证顶层 PS2_CLK/PS2_DAT 跳变、RTL 帧接收、APB 读访问、AM_INPUT_KEYBRD 调用
- 顺手修复 klib 的 `%c` 格式缺失

另一个因素：NVBoard 输入脚在初始化阶段必须保持协议定义的空闲电平（UART_RX=1、PS2_CLK=1、PS2_DAT=1），否则接收器在启动早期误判为 start bit，积累伪输入。

### Host 侧 vs Guest 侧日志的关键区分

- `sim_soc/*.cpp` 中的 `printf` 和 Verilog `$display/$write` 属于宿主机/仿真器侧日志，只打印到终端
- `am/src/riscv/ysyxsoc/*.c`、测试程序、`klib` 中的 `printf` 属于 guest 侧输出，通过 `putch()` 写 UART16550，从 DUT UART TX 引脚发出
- 在 `ioe.c` / `input.c` 中加 `printf` 不是无副作用调试日志，而是在被调试的 I/O 路径上注入 guest 串口输出，会改变时序、污染串口观测、制造 Heisenbug
- `am-tests` 的 `keyboard_test()` 开头的 `printf("Try to press any key ...")` 会导致程序卡在 UART 输出，键盘轮询被显著推迟

### Bootloader 与 UART 交接时序问题

`start.S` 的 SSBL 在跳转 `_trm_init` 前输出 `M` 和换行，而 `_trm_init()` 立即重新执行 `uart_init()`。如果前一轮发送尚未完全空闲，重配 UART 寄存器会把后续连续多字符输出带入异常窗口。

修复：`trm.c` 在 `_trm_init()` 中先等待 UART 同时满足 `THRE|TEMT`，再执行 `uart_init()`，进入 `main()` 前再次等待发送器完全空闲。

### `.data` 初始化错位根因（#35 + 2026-03-08）

**最终结论**：`am/src/riscv/ysyxsoc/linker.ld` 中 `.rodata` 末尾只对齐到 4 字节，`.data` 开头对齐到 8 字节，产生 4 字节 VMA 空洞。SSBL 线性整段搬运假设 VMA/LMA 连续，导致 `.data` 整体向后错位 4 字节。所有 `.data` 变量初值都可能错位，函数指针表最容易中招。

这个 bug 解释了之前的现象：
- `ioe.c` 的 `lut[128]` 函数指针表位于 `.data`，初值不可靠
- 加 guest 侧 `printf` 后现象变化——只是改变了链接布局，使错位后的 `.data` 呈现不同的坏相
- 看起来像"函数指针被打印影响了"或"`jalr` 跳错了"

关键复现实验（`probe/rodata_probe.c`）：
- 修复前：`lut[1]` 在进入 `main()` 时就已经不是 ELF 里应有的 `0xa000000c`，而是 0
- 修复后：`lut[1]` 正确为 `0xa000000c`

修复：`.rodata` 末尾对齐从 `ALIGN(4)` 改为 `ALIGN(8)`，消除 VMA 空洞。

排查过程：
1. 先怀疑 `ioe.c` 函数指针分发表，改成 `switch-case`，现象稳定但只是绕开症状
2. 怀疑 `jalr` 目标地址低位被错误清零
3. probe 发现问题在 `main()` 进入时就已存在，`lut[1]` 初值就错了
4. 结合 `readelf -S/-s` 定位到 `.rodata` 与 `.data` 之间的 4 字节 VMA 空洞

### Clangd 配置详细过程

依赖关系：
- am-tests 入口只定义 SRCS，然后 include `$(AM_HOME)/Makefile`
- 真正的编译规则在 `/home/lj/ysyx-workbench/abstract-machine/Makefile`
- `ARCH=riscv32im-ysyxsoc` 走 `am/scripts/riscv32im-ysyxsoc.mk` → `am/scripts/platform/ysyxsoc.mk`

做的事：
- 新增 `.clangd`，固定 clangd 从仓库根找数据库
- 新增 `tools/gen_compile_commands.sh`：清理重建 sim_soc / am / klib / am-tests，用 bear 统一追加到根目录 compile_commands.json，按真实路径去重
- 放开 gitignore 让 .clangd 和脚本可跟踪

多目录工作区 clangd 最佳实践：
- 永远只维护一个"根" compile_commands.json
- 数据库里的 file 最好是绝对路径
- 所有跨目录构建都汇总到这一个数据库
- 尽量从仓库真实路径打开文件，不要从外部 symlink 路径打开
- 每次改了构建参数、ARCH、头文件路径、切换 target 后，都重跑一次脚本
