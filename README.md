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