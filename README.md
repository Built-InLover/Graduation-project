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
