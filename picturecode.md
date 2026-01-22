graph LR
    %% 输入定义
    EXU_IN[EXU Result]
    LSU_IN[LSU/Load Result]
    
    %% 仲裁逻辑
    subgraph Arbitration_Logic [WBU仲裁器]
        LSU_BUSY{LSU Busy?}
        LSU_VALID{LSU Valid?}
        EXU_FIRE[exu_fire]
    end

    %% 信号流向
    LSU_IN --> LSU_VALID
    LSU_VALID -- "Yes (Commit)" --> INST_COMMIT((inst_commit))
    LSU_VALID -- "No" --> LSU_BUSY
    
    LSU_BUSY -- "Busy or Valid" --> EXU_FIRE
    EXU_FIRE -- "Disable" --> EXU_READ[1io.exuIn.ready = 0]
    EXU_FIRE -- "Enable" --> EXU_READY[io.exuIn.ready = 1]

    EXU_IN --> EXU_READY
    EXU_READY -- "Fire" --> INST_COMMIT
    
    %% 输出
    INST_COMMIT --> DIFFTEST[Update debug_pc / Difftest]
仲裁器图片

graph LR
    %% 状态定义
    IDLE((s_idle))
    WAIT_N(s_wait_normal_resp)
    WAIT_J(s_wait_jump_resp)
    FLUSH(s_flush)

    %% 初始状态
    START[开始] --> IDLE

    %% 正常流程
    IDLE -- "Bus Req Fire<br/>(无跳转)" --> WAIT_N
    WAIT_N -- "Bus Resp Valid<br/>(写回 PC+4)" --> IDLE

    %% 跳转流程
    IDLE -- "Bus Req Fire<br/>(有跳转)" --> WAIT_J
    WAIT_J -- "Bus Resp Valid<br/>(写回 TargetPC)" --> IDLE

    %% 冲突与冲刷流程
    WAIT_N -- "检测到跳转边沿" --> FLUSH
    WAIT_J -- "检测到再次跳转" --> FLUSH
    FLUSH -- "Bus Resp Valid<br/>(丢弃该包)" --> IDLE
IFU状态机图片

graph LR
    %% 核心模块定义
    subgraph Frontend [前端]
        IFU[IFU<br/>取指单元]
    end

    subgraph Buffers [级间缓冲]
        Q1[[Queue<br/>depth=1]]
        Q2[[Queue<br/>depth=1]]
    end

    subgraph Backend [后端]
        IDU[IDU<br/>译码单元]
        EXU[EXU<br/>执行单元]
        LSU[LSU<br/>访存单元]
        WBU[WBU<br/>写回仲裁]
    end

    subgraph Memory_System [存储系统]
        MEM[(Memory model)]
    end

    %% 指令主流水线
    IFU --> Q1
    Q1 --> IDU
    IDU --> Q2
    Q2 --> EXU
    
    %% EXU 后向分流
    EXU -- "Non-Load/Store" --> WBU
    EXU -- "Load/Store" --> LSU
    LSU --> WBU

    %% 总线连接
    IFU -- "Simple Bus" --> MEM
    LSU -- "Simple Bus" --> MEM


    %% 控制反馈信号 (红色)
    EXU -. "is_jump / redirect" .-> IDU
    EXU -. "is_jump / redirect" .-> IFU
整体框架图

graph TD
    %% 核心节点定义
    Start((基于Chisel的RISC-V SoC研究)) --> PC[1. 核心处理器核微架构开发]
    Start --> SoC[2. SoC总线与存储架构构建]
    Start --> Periph[3. 关键外设IP定制化集成]
    Start --> Verify[4. 验证体系与原型部署]

    %% 1. 处理器核细分
    subgraph "核心处理器 (RV32IMC)"
    PC --> ISA[指令系统全集成: RV32I + M + C]
    PC --> Pipe[流水线优化: 3/5级流水线+指令缓冲]
    PC --> Priv[特权架构: CSR寄存器+中断异常]
    end

    %% 2. SoC架构细分
    subgraph "系统互联与存储"
    SoC --> Bus[总线拓扑: AMBA AHB-Lite/APB]
    SoC --> Mem[存储层次: ROM Bootloader + SRAM]
    end

    %% 3. 外设IP细分
    subgraph "外设IP组件"
    Periph --> UART[UART: 串口通信+FIFO]
    Periph --> Timer[Timer: 64位实时定时器]
    Periph --> GPIO[GPIO: 通用输入输出]
    end

    %% 4. 验证体系细分
    subgraph "多维验证与实测"
    Verify --> Sim[Verilator周期精确级仿真]
    Verify --> Diff[DiffTest: RTL与Spike协同比对]
    Verify --> FPGA[FPGA原型验证与C驱动实测]
    end

    %% 闭环关系
    ISA -.-> Verify
    Bus -.-> FPGA
    FPGA --> End((系统功能与性能达标))
设计流程图

graph TD
    %% 软件侧
    subgraph "软件测试激励 (Software Stimulus)"
        Binary[riscv-tests / C Programs] --> HEX[Hex/Bin Files]
    end

    %% 仿真核心
    subgraph "自动化验证框架 (DiffTest Framework)"
        direction TB
        HEX --> |加载至存储器| DUT[设计目标: RTL SoC<br/>Verilator]
        HEX --> |同步执行| REF[黄金模型: Spike<br/>Reference Model]
        
        DUT --> |提交状态: PC/Reg/CSR| Compare{状态比对器<br/>Comparator}
        REF --> |标准状态: PC/Reg/CSR| Compare
    end

    %% 结果处理
    Compare --> |一致| Next[执行下一条指令]
    Compare --> |异常/不一致| Error[抛出错误并捕获波形<br/>Waveform Dump]
    
    %% 硬件侧
    subgraph "硬件原型验证 (Hardware)"
        DUT -.-> Vivado[Vivado 综合布线]
        Vivado --> FPGA[FPGA 开发板实测]
        FPGA --> UART[串口信息回传]
    end
测试流程示意图