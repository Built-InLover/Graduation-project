目前是流水线处理器
内部总线采用异步
外部总线也是异步
内部分为IFU IDU EXU LSU WBU五大模块
IFU和IDU  IDU和EXU之间存在queue(1,flow)
EXU的跳转指令会影响IFU和IDU行为，具体如下：
1、IFU会停止向后发送指令
2、IFU和IDU的queue会reset，保证queue变成空，等带IFU来覆盖
3、IDU静默，不处理
4、EXU不会继续向IDU和EXU之间的queue索要数据

未处理数据冒险，结构冒险，内存使用DPIC，但人为使得数据会在下一周期返回
EXU里面集成ALU BRU，同时没有专门对ALU和BRU进行配置，使用默认运算符，先进行功能测试

-----new------
初步写了与仿真环境交互的接口，还有ebreak的仿真实现，还没进行测试

-----new------
通过默认程序，仿真环境及对应difftest初步通过

-----new------
完善了ifu的状态机，解决了跳转导致的流水线冲刷问题
在WBU加入了EXU和LSU的仲裁，保证了顺序提交
同时取消了debug黑盒，全部在仿真部分主动读取接口，使得仿真时序得到大大改善
inst_over与debug_pc一一相对，在inst_over为1的情况下，debug_pc相对的就是当前结束的指令

目前遇到LOAD-USE冒险，导致程序无法继续执行

------new------
添加picture.md

------new------
1、初步解决了RAW 和 load-use
解决方法如下：
在idu获取当前在exu和lsu的指令，判断他们是不是load，以及他们有没有rd和idu当前所需要的src1 src2冲突，
如果有，不涉及load-use直接forward；
如果涉及load-use，就stall，然后再forward
forward我写了一个匹配函数，idu的src可能来自这周期刚算出来的exu，这周期的待写回exu（存在queue里）,这周期的待写回lsu（queue里）
然后根据匹配结果，找到距离自己最近的，直接forwarding过来，load-use的也是先install，然后拿到数据后会再进行匹配。
2、流水线IFU
创建了meta_queue，IFU的流水线队列，实现了每周期取值，同时通过epoch来识别跳转前后有效无效指令
3、加入了内存到IFU的缓冲区
因为现在IFU每周期都会发送指令请求，但是当前回送的数据当时的周期可能刚好由于后面阻塞反馈到了IFU使得IFU无法接受，那么此时就暂存。
本质上是因为，IFU每周期都发送请求，但不确定数据返回时IFU的状态。
4、加入了order_q保证了顺序退休，每一个经过IDU的指令，都会写入这个order_q，然后wbu读取，和自己的两个输入比较，只有匹配才运行退休，
如果出现ifu要跳转，那么这个order_q不需要清空，我采用了epoch标志位，如果发现当前退休的指令和order_q里面的epoch不相同，说明是跳转过后的指令，自动舍弃
5、修改store也为握手信号，而不是一发送就当结束
目前L/S都为2周期结束

目前的QUEUE汇总：meta_queue实现IFU流水线访存和epoch冲刷 ifu_resp_q取值缓冲 order_q顺序提交 还有各级之间的缓冲寄存器，阻断反馈逻辑，提高主频
其中LSU有寄存器保存指令，本质是IDU和EXU和WBU现在都可以保证一个周期完成指令，所以不需要寄存器，而LSU需要2个周期，所以必然要保存状态，区分正在工作和即将工作

阶段性成果，跑通第一个add程序

debug心得：尝试观察一条出错指令从被取出到退休的全过程，慢慢定位，如果是第一次出现，可能是指令实现问题，如果出现很多次，考虑不同指令组合带来的阻塞延迟的时序问题。

-----new------
修复了load指令unliagned问题

-----new------
合并了load-use的stall和raw的数据前向传递
跑通了RV32e的全部测试集

------new--------
把mycore从npc独立出来，创建了riscv32i riscv32im riscv32imc三个架构，不用再跑rv32e的测试了
实现了M扩展，通过了RISCV32M测试和ysyx框架下的cpu-tests

------new--------
完成了M扩展的不完全测试
成功运行了rt-thread-am
最后无法输出msh >/ 是因为uart.c 里面的uart_getc输出完预设内容后就输出-1，导致shell.c里面的finsh_getchar读不到字节而卡住
至于nemu和native为啥不会，而是弹出一个界面，暂时未知，没兴趣弄明白了
至于要解决的话，一个是弄明白为啥nemu和native会弹窗口，还有一个就是shell.c里实现一个阻塞等待，而不是读不到字节直接卡死

------new--------
解决了nemu和npc对ebreak处理不同的情况：直接在execute里判断是否是ebreak，如果是，直接不进行difftest测试，而是直接结束
将SimpleBus升级为AXI4-Lite，但是简化了实现，Store 必须将AW和W绑定，以及默认LSU读比写优先

------new--------
在RAM模块里加入随机数，引入延迟，成功运行了rt-thread
RTL原生支持了UART和TIMER，但是还是通过仿真环境实现

下一步打算使用ysyxSoc提供的框架，完整实现uart等外设

目前还要处理的是：
1、外设与中断
2、I-Cache / D-Cache (性能优化)
3、C 扩展

------new--------
完成了NPC接入ysyxSoC的工作，彻底脱离了mycore的DPI-C虚拟仿真环境。

架构变化：
旧架构中IFU和LSU通过DPI-C直连虚拟RAM（MemBlackBox），一拍完成访存，没有真实总线。
新架构中IFU和LSU通过AXI4总线发请求，经ysyxSoC的AXI4 Crossbar路由到真实外设（mrom/flash/sdram/uart16550/spi等）。

新架构拓扑：
```
              ┌──────────────────────────────────┐
              │         ysyx_23060000             │
              │                                   │
              │   IFU ──AXI4──┐                   │
              │               ├── 地址路由         │
              │   LSU ──AXI4──┘                   │
              │         │           │             │
              │    0x0200_xxxx    其他地址          │
              │         │           │             │
              │      CLINT    IFU/LSU仲裁(LSU优先) │
              │                     │             │
              └─────────────────────┼─────────────┘
                                    │ AXI4 Master
                                    ▼
              ┌──────────────────────────────────┐
              │          ysyxSoCFull              │
              │   AXI4 Crossbar → 地址译码        │
              │    ┌────┬────┬────┬────┐          │
              │  mrom flash sdram uart spi ...    │
              └──────────────────────────────────┘
```

具体改动：
1、AXI4Lite升级为完整AXI4（id/len/size/burst/last），IFU和LSU原生AXI4
2、ysyx_23060000顶层符合cpu-interface.md规范，内含DistributedCore + CLINT
3、LSU地址路由：CLINT(0x0200_xxxx)走内部，其余走外部Master
4、IFU和LSU共享一个AXI4 Master端口，LSU优先仲裁
5、Slave接口全部赋0，不使用的输入悬空
6、清理了所有旧的DPI-C虚拟外设代码（RAM.scala/SoCTop.scala/AXI4Lite相关/SimpleBus等）
7、仿真环境迁移到sim_soc/，顶层为ysyxSoCFull，verilator编译通过

当前状态：仿真可运行100万周期无崩溃，但CPU无有效输出（预期行为）
原因：CPU复位PC还未对齐ysyxSoC地址映射，flash_read/mrom_read尚未实现

下一步：
1、实现flash_read/mrom_read，让CPU能从mrom/flash取到指令
2、调整CPU复位PC匹配ysyxSoC启动地址（mrom 0x20000000）
3、适配AM的NPC平台到ysyxSoC地址映射（UART 0x10000000等）
4、恢复difftest

------new--------
UART字符输出测试通过，CPU成功通过AXI4总线写入UART16550外设。

验证内容：
1、编写最简char-test.c，直接往UART THR(0x10000000)写入字符'A'，无需初始化和LSR轮询
2、ysyxSoC的UART RTL（uart_tfifo.v）内置$write("%c", data_in)，FIFO push时直接在终端打印
3、Makefile添加riscv32i交叉编译目标，链接地址0x20000000（MROM），objcopy生成纯二进制
4、test_bench_soc.cpp加载char-test.bin到MROM缓冲区，CPU从MROM取指执行store到UART
5、verilator添加--autoflush选项，解决$write无换行时stdout缓冲不刷新的问题
6、清理了test_bench_soc.cpp中的UART串口解码器和调试日志（MROM读取日志、TX边沿日志等）

踩坑记录：
- 不加-O2编译时，gcc生成函数序言（addi sp,-16; sw s0,12(sp)），sp未初始化导致store到非法地址，AXI总线挂死
- 解决方案：编译加-O2，编译器优化掉函数序言，直接生成lui+sb指令序列

当前状态：CPU从MROM取指 → AXI4总线 → 写UART THR → 终端输出字符'A'，全链路验证通过

下一步：
1、实现flash_read，让CPU能从flash取到指令，加载更大程序
2、跑通更多cpu-tests（可能需要.data搬运逻辑）
3、恢复difftest功能

------new--------
创建AM运行时环境 riscv32im-ysyxsoc，cpu-tests/dummy在ysyxSoC仿真中通过。

内存布局：代码在MROM(0x20000000, 4KB)，数据/栈在SRAM(0x0f000000, 8KB)，UART在0x10000000。
链接脚本实现MROM/SRAM分离：.text+.rodata放MROM，.data+.bss+栈放SRAM。
AM平台只包含最小TRM（start.S + trm.c），无IOE/CTE。
sim_soc支持IMG=参数指定bin文件路径，不再硬编码char-test.bin。

验证：dummy测试48周期内ebreak正常退出。

下一步：
1、实现flash_read，突破MROM 4KB限制
2、跑通更多cpu-tests
3、恢复difftest功能