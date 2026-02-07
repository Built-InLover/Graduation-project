package corewithbus

import chisel3._
import chisel3.util._
import essentials._
import core._

class EXU extends Module with HasInstrType {
  val io = IO(new Bundle {
    // ... 接口保持不变 ...
    val in = Flipped(Decoupled(new Bundle {
      val pc       = UInt(32.W)
      val src1     = UInt(32.W)
      val src2     = UInt(32.W)
      val imm      = UInt(32.W)
      val fuType   = FuType()    
      val fuOp     = FuOpType()  
      val rfWen    = Bool()
      val rdAddr   = UInt(5.W)
      val isLoad   = Bool()
      val isStore  = Bool()
      val isBranch = Bool()
      val isJump   = Bool()
      val useImm   = Bool()
      val uop_id   = UInt(4.W)
    }))

    val lsuOut = Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val addr   = UInt(32.W)
      val wdata  = UInt(32.W)
      val func   = FuOpType()    
      val rdAddr = UInt(5.W)
      val uop_id = UInt(4.W) 
    })

    val wbuOut = Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W) 
      val data   = UInt(32.W)
      val rdAddr = UInt(5.W)
      val rfWen  = Bool()
      val uop_id = UInt(4.W) 
    })

    val redirect = Valid(new Bundle {
      val targetPC = UInt(32.W)
      val is_privileged   = Bool()   // [新增] 区分是 Branch 还是 Trap/Mret
    })

    val debug_csr   = Output(Vec(4, UInt(32.W)))

    val slow_op_pending = Output(Vec(2, new Bundle { 
      val busy = Bool()
      val rd   = UInt(5.W)
      val id   = UInt(4.W)
    }))

    val rob_empty = Input(Bool())
  })

  // =======================================================
  // 1. 实例化功能单元 (新增 CSRUnit)
  // =======================================================
  val alu        = Module(new AluUnit)
  val multiplier = Module(new Multiplier)
  val divider    = Module(new Divider)
  val csr        = Module(new CSRUnit) // [新增]

  // =======================================================
  // 2. 解码与 Dispatch (分发)
  // =======================================================
  val in_valid = io.in.valid
  val fuType   = io.in.bits.fuType
  val fuOp     = io.in.bits.fuOp
  
  // 基础解码
  val isMulRaw = in_valid && fuType === FuType.mdu && MDUOpType.isMul(fuOp)
  val isDivRaw = in_valid && fuType === FuType.mdu && MDUOpType.isDiv(fuOp)
  val isMemRaw = in_valid && fuType === FuType.lsu
  val isCsrRaw = in_valid && fuType === FuType.csr // [新增]
  // ALU 接管剩下的有效指令
  val isALURaw = in_valid && !isMulRaw && !isDivRaw && !isMemRaw && !isCsrRaw

  // --- 状态监测 (互斥锁) ---
  val mul_busy = !multiplier.io.in.ready
  val div_busy = !divider.io.in.ready
  
  // [MDU 锁]：乘除法忙，或者刚出结果，都视为占用
  val mdu_locked = mul_busy || div_busy || multiplier.io.out.valid || divider.io.out.valid

  // =======================================================
  // CSR 状态保活逻辑 (Robust Version)
  // =======================================================
  val csr_pending = RegInit(false.B)
  // 定义事件
  val csr_fire_start = io.in.fire && isCsrRaw
  val csr_fire_done  = csr.io.out.fire
  // 状态机更新逻辑：
  // 1. 如果做完了 (done)，立刻清除 busy 标记
  // 2. 如果开始了但没做完 (start && !done)，标记为 busy
  // 3. 如果既开始又做完了 (start && done)，busy 保持为 false (单周期直通)
  
  when (csr_fire_done) {
    csr_pending := false.B
  } .elsewhen (csr_fire_start) {
    csr_pending := true.B
  }
  
  // 解释：Chisel 的 when-elsewhen 是有优先级的。
  // 如果 simultaneous (同时发生)：
  // Case A: start=1, done=1. 进入第一个 when -> pending := false. (正确！单周期完成)
  // Case B: start=1, done=0. 进入第二个 when -> pending := true.  (正确！卡住了，下一周期保持 valid)
  // Case C: start=0, done=1. 进入第一个 when -> pending := false. (正确！多周期刚做完)
  
  // 1. 初始准入：队列空 且 MDU空 (给 IDU 的 Ready 用)
  // 注意：如果正在 pending，ready 必须为 false，防止 IDU 发下一条
  val csr_can_dispatch = io.rob_empty && !mdu_locked && !csr_pending


  // --- 连线 ALU ---
  // [互斥] MDU 忙的时候，ALU 暂停接客
  alu.io.in.valid       := isALURaw && !mdu_locked && io.in.ready
  alu.io.in.bits.src1   := io.in.bits.src1
  alu.io.in.bits.src2   := io.in.bits.src2
  alu.io.in.bits.imm    := io.in.bits.imm
  alu.io.in.bits.pc     := io.in.bits.pc    
  alu.io.in.bits.fuOp   := fuOp
  alu.io.in.bits.useImm := io.in.bits.useImm
  alu.io.in.bits.isBranch := io.in.bits.isBranch
  alu.io.in.bits.isJump   := io.in.bits.isJump
  alu.io.in.bits.isMem    := isMemRaw

  // --- 连线 MUL ---
  multiplier.io.in.valid := isMulRaw && !mul_busy && io.in.ready// 只要乘法器不忙就可以进
  multiplier.io.in.bits.src1   := io.in.bits.src1
  multiplier.io.in.bits.src2   := io.in.bits.src2
  multiplier.io.in.bits.fuOp   := fuOp
  multiplier.io.in.bits.uop_id := io.in.bits.uop_id
  multiplier.io.in.bits.rdAddr := io.in.bits.rdAddr
  multiplier.io.in.bits.rfWen  := io.in.bits.rfWen
  multiplier.io.in.bits.pc     := io.in.bits.pc 

  // --- 连线 DIV ---
  divider.io.in.valid    := isDivRaw && !div_busy && io.in.ready// 只要除法器不忙就可以进
  divider.io.in.bits.src1    := io.in.bits.src1
  divider.io.in.bits.src2    := io.in.bits.src2
  divider.io.in.bits.fuOp    := fuOp
  divider.io.in.bits.uop_id  := io.in.bits.uop_id
  divider.io.in.bits.rdAddr  := io.in.bits.rdAddr
  divider.io.in.bits.rfWen   := io.in.bits.rfWen
  divider.io.in.bits.pc      := io.in.bits.pc 

  // --- 连线 CSR [新增] ---
  // CSR 也是单周期(通常)，为了安全，必须等 MDU 空闲才能执行，
  // 同时也防止 CSR 修改了状态(如 MSTATUS) 导致后续还在流水线里的指令出错，
  // 所以 CSR 和 MDU 严格互斥。
  csr.io.in.valid     := isCsrRaw && (csr_can_dispatch || csr_pending) && io.in.ready// 持续驱动 Valid：要么是刚准入，要么是正在跑
  csr.io.in.bits.src1  := io.in.bits.src1
  csr.io.in.bits.imm   := io.in.bits.imm
  csr.io.in.bits.func  := io.in.bits.fuOp
  csr.io.in.bits.uop_id := io.in.bits.uop_id
  csr.io.in.bits.pc    := io.in.bits.pc
  io.debug_csr          := csr.io.debug_csr

  // =======================================================
  // 3. 结果仲裁 (Arbitration)
  // =======================================================
  
  val alu_out = alu.io.out.bits
  val mul_out = multiplier.io.out.bits
  val div_out = divider.io.out.bits
  val csr_out = csr.io.out.bits

  val mul_valid = multiplier.io.out.valid
  val div_valid = divider.io.out.valid
  val csr_valid = csr.io.out.valid

  // [新增] 判断当前 CSR 指令是否是 Trap/Ret 类
  // 我们可以利用 redirect 信号来判断，因为 redirect 只有在 JMP 类指令时有效
  val is_csr_trap_or_ret = csr.io.redirect.valid

  // --- DNPC 选择 ---
  val wbu_dnpc = MuxCase(alu_out.dnpc, Seq(
    mul_valid -> (mul_out.pc + 4.U),
    div_valid -> (div_out.pc + 4.U),
    csr_valid -> Mux(is_csr_trap_or_ret, csr.io.redirect.bits.targetPC, io.in.bits.pc + 4.U)
  ))

  // --- Data 选择 ---
  val wbData_ALU = Mux(io.in.bits.isJump, io.in.bits.pc + 4.U, alu_out.data)

  val wbu_data = MuxCase(wbData_ALU, Seq(
    mul_valid -> mul_out.data,
    div_valid -> div_out.data,
    csr_valid -> csr_out.data  // CSR 读出的数据
  ))

  // --- 其他信号选择 ---
  val wbu_rd = MuxCase(io.in.bits.rdAddr, Seq(
    mul_valid -> mul_out.rdAddr,
    div_valid -> div_out.rdAddr,
    csr_valid -> io.in.bits.rdAddr // CSR 指令通常写回原指令指定的 rd
  ))
  
  val wbu_id = MuxCase(io.in.bits.uop_id, Seq(
    mul_valid -> mul_out.uop_id,
    div_valid -> div_out.uop_id,
    csr_valid -> csr_out.uop_id
  ))

  val wbu_wen = MuxCase(io.in.bits.rfWen, Seq(
    mul_valid -> mul_out.rfWen,
    div_valid -> div_out.rfWen,
    // 如果是 CSR Valid：
    // 1. 如果是 Trap/Ret (redirect valid)，强制为 false (不写 RegFile)
    // 2. 如果是普通 CSR，强制为 true (原子交换性)
    csr_valid -> (true.B && !is_csr_trap_or_ret)
  ))

  val wbu_pc = MuxCase(io.in.bits.pc, Seq(
    mul_valid -> mul_out.pc,
    div_valid -> div_out.pc,
    csr_valid -> io.in.bits.pc
  ))

  // =======================================================
  // 4. 输出与握手
  // =======================================================

  val downstream_ready = Mux(isMemRaw, io.lsuOut.ready, io.wbuOut.ready)

  // 3. 反压 IDU：只有能 Dispatch 时才 Ready
  val csr_ready_safe = csr.io.in.ready && csr_can_dispatch

  // Ready 逻辑：
  // 1. MDU 不忙
  // 2. 下游 Ready
  // 3. 具体的功能单元 Ready
  io.in.ready := !mdu_locked && MuxCase(downstream_ready, Seq(
    isDivRaw -> divider.io.in.ready,
    isMulRaw -> multiplier.io.in.ready,
    isCsrRaw -> csr_ready_safe,
    isALURaw -> alu.io.in.ready
  ))

  // 反压逻辑
  alu.io.out.ready        := downstream_ready && !mdu_locked
  multiplier.io.out.ready := io.wbuOut.ready
  divider.io.out.ready    := io.wbuOut.ready
  csr.io.out.ready        := io.wbuOut.ready // [新增]

  // WBU Valid
  // 注意：如果是 ebreak，csr_valid 是 false，
  // 但如果你希望 DPI-C 在 ebreak 时也能收到 valid 包，你需要在这里做个 hack。
  // 正常 CPU 设计：Trap 指令不走 Writeback，直接 Redirect。
  // 你的 DPI-C 需求：可能需要在 Writeback 阶段看到 ebreak。
  // 建议：DPI-C 最好监听 io.redirect 信号，而不是 io.wbuOut。
  // 但为了兼容你现有的逻辑，这里保持原样：Trap 不产生 wbuOut.valid。
  io.wbuOut.valid       := multiplier.io.out.valid || divider.io.out.valid || csr_valid || (alu.io.out.valid && !mdu_locked && !isMemRaw)
  
  io.wbuOut.bits.pc     := wbu_pc
  io.wbuOut.bits.dnpc   := wbu_dnpc
  io.wbuOut.bits.data   := wbu_data
  io.wbuOut.bits.rdAddr := wbu_rd
  io.wbuOut.bits.rfWen  := wbu_wen
  io.wbuOut.bits.uop_id := wbu_id

  // LSU Valid
  // 必须加 !isCsrRaw，防止 CSR 指令误入 LSU
  io.lsuOut.valid       := io.in.valid && isMemRaw && !mdu_locked
  io.lsuOut.bits.pc     := io.in.bits.pc
  io.lsuOut.bits.dnpc   := wbu_dnpc 
  io.lsuOut.bits.addr   := alu_out.data 
  io.lsuOut.bits.wdata  := io.in.bits.src2
  io.lsuOut.bits.func   := io.in.bits.fuOp
  io.lsuOut.bits.rdAddr := io.in.bits.rdAddr
  io.lsuOut.bits.uop_id := io.in.bits.uop_id

  // Redirect 逻辑 (合并 ALU Branch 和 CSR Trap)
  val is_alu_transacting = 
  io.wbuOut.fire &&          // 1. 确实有一条指令正在退休/写回
  !mdu_locked &&             // 2. MDU 不忙 (说明不是乘除法占着总线)
  !isMemRaw &&               // 3. 不是访存指令
  !csr.io.out.valid          // 4. (可选) 不是 CSR 指令

  val is_alu_redirect = is_alu_transacting && alu_out.taken
  val is_csr_redirect = csr.io.redirect.valid // [新增] 包含 EBREAK/ECALL/MRET

  io.redirect.valid     := is_alu_redirect || is_csr_redirect
  io.redirect.bits.is_privileged    := is_csr_redirect // 告诉 IFU 这是 Trap/Return
  // CSR 的跳转优先级高于 ALU (虽然理论上互斥不会同时发生)
  io.redirect.bits.targetPC := Mux(is_csr_redirect, csr.io.redirect.bits.targetPC, alu_out.targetPC)

  io.slow_op_pending(0) <> divider.io.pending
  io.slow_op_pending(1) <> multiplier.io.pending
}
