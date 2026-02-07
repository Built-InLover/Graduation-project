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

目前还要处理的是：
SimpleBus -> AXI4-Lite (基础设施升级)
随机延迟 DRAM 模型 (验证 AXI 握手的试金石)
I-Cache / D-Cache (性能优化)
外设与中断
C 扩展