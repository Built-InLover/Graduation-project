现在的 forward_sources 本质上就是一张分布式的记分牌（Scoreboard）。
3 个可用源 (Valid=1)：就像是记分牌上写着“计算完毕，数据是 X”。
1 个等待源 (Valid=0)：就像是记分牌上写着“LSU 正在写 x5 寄存器，但数据还没回来，谁要用 x5 就得在门口等着”。
关于 EXU 变多周期的推演
如果你的 EXU 加入了乘除法（M 扩展）或者浮点运算，变成了多周期单元，情况会分为两种，但结论都是：必须加等待源。
情况 A：阻塞式多周期（Iterative / Blocking）
假设你做了一个除法器，需要计算 32 个周期。在这 32 个周期里，EXU 是忙碌的（state = s_busy），不接受新指令。
这时候，EXU 的状态和现在的 LSU 是一模一样的：
内部状态：有一个 uop_id 和 rd_addr 正在被占用。
数据状态：结果还没算出来。
Top 层的做法： 你需要从 EXU 引出一个 is_busy 信号和内部的 current_uop_id、current_rd。 然后在 Top 里增加第 5 个 source：
Scala
// 5. EXU 正在忙于多周期计算 (例如除法)
mkFwd(
  pend   = exu.io.is_busy,       // EXU 说它正忙
  valid  = false.B,              // 结果还没算好
  rd     = exu.io.busy_rd,       // 正在算的那个目标寄存器
  data   = 0.U,                  // 占位
  id     = exu.io.busy_uop_id
)
情况 B：全流水线式多周期（Fully Pipelined）
假设你做了一个高性能浮点加法器，分为 3 级流水线（F1 -> F2 -> F3）。每一拍都可以进一条新指令。 此时，EXU 肚子里可能同时有 3 条指令在跑！
F3 (快出结果了): 写 x1, id=10
F2 (算了一半): 写 x2, id=11
F1 (刚进来): 写 x3, id=12
这种情况下，每一级流水线本质上都是一个“源”。你需要把每一级都暴露给 IDU：
Scala
    // 针对流水线每一级都要做一个 Forwarding Source
    // Stage 3 (马上出结果，可能可以直接 bypass 数据)
    mkFwd(pend=true.B, valid=true.B, rd=exu.s3_rd, data=exu.s3_data, id=exu.s3_id),
    // Stage 2 (还在算，数据不可用) -> 必须 Pend 住，防止 IDU 拿旧数据
    mkFwd(pend=true.B, valid=false.B, rd=exu.s2_rd, data=0.U, id=exu.s2_id),
    // Stage 1 (刚进来，数据不可用)
    mkFwd(pend=true.B, valid=false.B, rd=exu.s1_rd, data=0.U, id=exu.s1_id),
总结：通用的心法
不管以后你的处理器加什么复杂的单元（比如 Vector 单元、RoCC 加速器），只要遵循这个**“占坑原则”**，你的流水线就是坚不可摧的：
只要一条指令进入了部件，还没退休：它就必须在一个 ForwardingBus 上把 pend 拉高，并亮出它的 uop_id 和 rd。
如果数据算好了：把 valid 拉高，把数据放上去。
如果数据没算好：把 valid 拉低。
IDU 只需要傻傻地执行那个 reduceLeft 逻辑：
"谁 ID 离我最近？哦是那个除法器。数据好了吗？没好？那我就躺平（Stall）等着，直到它变好。"
这就是现代处理器**Scoreboard（记分牌）**算法的分布式实现版本。你的思路非常清晰，已经掌握了乱序流水的精髓！


关于riscv-tests-am-master里面的测试程序分类：
我们来逐一拆解：
1. ua/ui/uc/um/ud/uf：指令集的横向切片
这些目录本质上是针对 指令集扩展 的模块化测试：
ui (Integer): 基础整数指令。
um (Multiplication): 乘除法。
ua (Atomic): 原子指令（用于多核同步）。
uc (Compressed): 压缩指令。
uf/ud (Float/Double): 浮点运算。
核心逻辑：之所以给它们冠以 u 前缀（如 rv32ui），是因为这些指令都是非特权指令。它们在任何模式（U/S/M）下都可以运行，且运行行为完全一致。
2. mi/si：特权架构的纵向切片
这两者才是真正的“模式测试”：
mi (Machine Integer)：测试 M 模式 特有的行为。
CSR 寄存器：mstatus, mtvec, mepc 等。
异常处理：非法指令异常、中断嵌套等。
特权指令：mret, wfi 等。
si (Supervisor Integer)：测试 S 模式 特有的行为。
虚存管理：页表转换（satp）、TLB 刷新（sfence.vma）。
S 态异常：sret, stvec, sepc。
3. 为什么没有单独的 rv32ui 之外的“U 模式测试”？
答案是：U 模式的本质是“限制”，而不是“新增”。
硬件实现上：实现 U 模式几乎不需要增加新指令（除了 ecall 和 uret），它主要是增加了一套权限检查逻辑。
测试逻辑上：
功能重合：在 U 模式下跑的指令，其运算逻辑已经在 ui/um/uc 中测过了。
权限边界测试被归类到了 mi：测试 U 模式是否真的“起作用”，本质上是测试 “当我在低权限模式下执行高权限指令时，硬件是否会报错”。
例如：在 U 模式下执行 csrw mstatus, x1。
这个测试点通常放在 rv32mi/illegal.S 里。它会先切换到 U 模式，尝试违规操作，然后看硬件是否能正确跳转回 M 模式的异常处理程序。
模式切换测试：从 M 进入 U，再通过 ecall 从 U 回到 M。这种“模式间跳转”的逻辑，也是在 mi（或环境测试）中完成的。
4. 总结：你该如何理解这些测试？
我们可以把 RISC-V 的核想象成一个酒店：
rv32ui/um...：测试酒店里的基础设施（水龙头是否有水、灯泡是否亮）。不管你是普通客人（U）还是总经理（M），这些东西用起来都一样。
rv32mi：测试总经理的特权（进入监控室、修改酒店大门密码、处理火警异常）。
rv32si：测试主管的权限（分配房间号、操作电梯层选控制）。
U 模式测试：不需要专门测 U 模式的“水龙头”，只需要在 mi 测试里验证“普通客人（U）不能进入监控室（M）”就可以了。
开发建议
如果你是先实现 M 模式 + I 指令：
第一步：跑通 rv32ui（虽然带 u，但在 M 模式下跑是完全合法的）。
第二步：跑通 rv32mi（验证你的 CSR 和异常处理是否能工作）。
第三步：如果你想支持真正的 U 模式，你需要在 mi 里的异常处理逻辑中，增加对“权限违规”的判定逻辑。
所以，ui 是测“能做什么”，mi 是测“能管什么”，而 U 模式的验证就藏在 mi 对“谁能做什么”的限制测试”的检查里”能管谁”的检查里。