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