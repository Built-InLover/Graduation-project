package corewithbus

import chisel3._
import chisel3.util._
import essentials._
import core._

class EXU extends Module with HasInstrType {
  val io = IO(new Bundle {
    // ... 输入输出接口保持完全一致 ...
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
      val is_csr = Bool()
      val uop_id = UInt(4.W) 
    })

    val redirect = Valid(new Bundle {
      val targetPC = UInt(32.W)
    })

    val slow_op_pending = Output(Vec(2, new Bundle { 
      val busy = Bool()
      val rd   = UInt(5.W)
      val id   = UInt(4.W)
    }))
  })

  // =======================================================
  // 1. 实例化三个功能单元
  // =======================================================
  val alu        = Module(new AluUnit)
  val multiplier = Module(new Multiplier)
  val divider    = Module(new Divider)

  // =======================================================
  // 2. 解码与 Dispatch (分发)
  // =======================================================
  val in_valid = io.in.valid
  val fuType   = io.in.bits.fuType
  val fuOp     = io.in.bits.fuOp
  
  val isMul = in_valid && fuType === FuType.mdu && MDUOpType.isMul(fuOp)
  val isDiv = in_valid && fuType === FuType.mdu && MDUOpType.isDiv(fuOp)
  val isMem = in_valid && fuType === FuType.lsu
  val isALU = in_valid && !isMul && !isDiv && !isMem 

  // 计算 MDU 占用 (我们之前修复好的逻辑)
  val mdu_done      = multiplier.io.out.valid || divider.io.out.valid
  val mdu_occupy    = !multiplier.io.in.ready || !divider.io.in.ready || mdu_done

  // --- 连线 ALU ---
  alu.io.in.valid       := (isALU || isMem) && !mdu_occupy
  alu.io.in.bits.src1   := io.in.bits.src1
  alu.io.in.bits.src2   := io.in.bits.src2
  alu.io.in.bits.imm    := io.in.bits.imm
  alu.io.in.bits.pc     := io.in.bits.pc     // 必须传 PC 进去算 DNPC
  alu.io.in.bits.fuOp   := fuOp
  alu.io.in.bits.useImm := io.in.bits.useImm
  alu.io.in.bits.isBranch := io.in.bits.isBranch
  alu.io.in.bits.isJump   := io.in.bits.isJump
  alu.io.in.bits.isMem    := isMem

  // --- 连线 MUL ---
  multiplier.io.in.valid       := isMul && !mdu_occupy
  multiplier.io.in.bits.src1   := io.in.bits.src1
  multiplier.io.in.bits.src2   := io.in.bits.src2
  multiplier.io.in.bits.fuOp   := fuOp
  multiplier.io.in.bits.uop_id := io.in.bits.uop_id
  multiplier.io.in.bits.rdAddr := io.in.bits.rdAddr
  multiplier.io.in.bits.rfWen  := io.in.bits.rfWen
  multiplier.io.in.bits.pc     := io.in.bits.pc 

  // --- 连线 DIV ---
  divider.io.in.valid        := isDiv && !mdu_occupy
  divider.io.in.bits.src1    := io.in.bits.src1
  divider.io.in.bits.src2    := io.in.bits.src2
  divider.io.in.bits.fuOp    := fuOp
  divider.io.in.bits.uop_id  := io.in.bits.uop_id
  divider.io.in.bits.rdAddr  := io.in.bits.rdAddr
  divider.io.in.bits.rfWen   := io.in.bits.rfWen
  divider.io.in.bits.pc      := io.in.bits.pc 

  // =======================================================
  // 3. 结果仲裁 (Arbitration)
  // =======================================================
  
  // 简化变量名
  val alu_out = alu.io.out.bits
  val mul_out = multiplier.io.out.bits
  val div_out = divider.io.out.bits

  val mul_valid = multiplier.io.out.valid
  val div_valid = divider.io.out.valid

  // --- DNPC 选择 (这就是你担心丢失的逻辑！) ---
  // 如果是 MUL/DIV，dnpc 肯定是 pc+4 (它们没有跳转功能)
  // 如果是 ALU，dnpc 来自 AluUnit 算好的结果 (可能是跳转目标，也可能是 pc+4)
  val wbu_dnpc = MuxCase(alu_out.dnpc, Seq(
    mul_valid -> (mul_out.pc + 4.U),
    div_valid -> (div_out.pc + 4.U)
  ))

  // --- Data 选择 ---
  // Jump 指令写回的是 PC+4，这个已经在 AluUnit 的 dnpc 计算里隐含了么？
  // 不，JAL/JALR 写回的是 link address (PC+4)。
  // 原来的逻辑：Mux(isJump, pc+4, aluResult)
  // 我们可以在 AluUnit 里算出这个 writeback data，或者在这里处理。
  // 为了保持 ALU 纯洁性，我们在 AluUnit 输出 data 时，如果是 Jump，可以直接输 PC+4 吗？
  // 最好保持 ALU 输出纯计算结果，Jump 的 +4 在这里选比较稳妥。
  
  val wbData_ALU = Mux(io.in.bits.isJump, io.in.bits.pc + 4.U, alu_out.data)

  val wbu_data = MuxCase(wbData_ALU, Seq(
    mul_valid -> mul_out.data,
    div_valid -> div_out.data
  ))

  // --- 其他信号选择 ---
  val wbu_rd = MuxCase(io.in.bits.rdAddr, Seq(
    mul_valid -> mul_out.rdAddr,
    div_valid -> div_out.rdAddr
  ))
  
  val wbu_id = MuxCase(io.in.bits.uop_id, Seq(
    mul_valid -> mul_out.uop_id,
    div_valid -> div_out.uop_id
  ))

  val wbu_wen = MuxCase(io.in.bits.rfWen, Seq(
    // 注意：Branch指令不写回，这在 IDU 已经处理了 rfWen=false
    // 或者在 ALU 处处理。原来的逻辑是 Mux(isBranch, false.B, rfWen)
    // 假设 IDU 传进来的 rfWen 对 Branch 来说已经是 false
    mul_valid -> mul_out.rfWen,
    div_valid -> div_out.rfWen
  ))

  val wbu_pc = MuxCase(io.in.bits.pc, Seq(
    mul_valid -> mul_out.pc,
    div_valid -> div_out.pc
  ))
  
  val wbu_is_csr = Mux(mdu_done, false.B, io.in.bits.fuType === FuType.csr)

  // =======================================================
  // 4. 输出与握手
  // =======================================================

  val downstream_ready = Mux(isMem, io.lsuOut.ready, io.wbuOut.ready)

  // Ready 逻辑：MDU 不忙，且下游 Ready，且对应单元 Ready
  io.in.ready := !mdu_occupy && MuxCase(downstream_ready, Seq(
    isDiv -> divider.io.in.ready,
    isMul -> multiplier.io.in.ready,
    isALU -> alu.io.in.ready // 其实就是 downstream_ready
  ))

  // 反压逻辑
  alu.io.out.ready        := downstream_ready && !mdu_occupy
  multiplier.io.out.ready := io.wbuOut.ready
  divider.io.out.ready    := io.wbuOut.ready

  // WBU Valid
  io.wbuOut.valid       := mdu_done || (alu.io.out.valid && !mdu_occupy && !isMem)
  
  io.wbuOut.bits.pc     := wbu_pc
  io.wbuOut.bits.dnpc   := wbu_dnpc // [恢复] 正确的 DNPC
  io.wbuOut.bits.data   := wbu_data
  io.wbuOut.bits.rdAddr := wbu_rd
  io.wbuOut.bits.rfWen  := wbu_wen
  io.wbuOut.bits.uop_id := wbu_id
  io.wbuOut.bits.is_csr := wbu_is_csr

  // LSU Valid
  io.lsuOut.valid       := io.in.valid && isMem && !mdu_done && !mdu_occupy
  io.lsuOut.bits.pc     := io.in.bits.pc
  io.lsuOut.bits.dnpc   := wbu_dnpc // LSU 也需要 dnpc (通常是pc+4，如果是分支后的 load 也不影响)
  io.lsuOut.bits.addr   := alu_out.data // LSU 的地址就是 ALU 算出来的结果
  io.lsuOut.bits.wdata  := io.in.bits.src2
  io.lsuOut.bits.func   := io.in.bits.fuOp
  io.lsuOut.bits.rdAddr := io.in.bits.rdAddr
  io.lsuOut.bits.uop_id := io.in.bits.uop_id

  // Redirect 逻辑
  // [Fix] 这里的 fire 必须结合 alu 的 taken
  val is_alu_transacting = io.wbuOut.fire && isALU && !mdu_occupy
  
  io.redirect.valid         := is_alu_transacting && alu_out.taken
  io.redirect.bits.targetPC := alu_out.targetPC

  io.slow_op_pending(0) <> divider.io.pending
  io.slow_op_pending(1) <> multiplier.io.pending
}