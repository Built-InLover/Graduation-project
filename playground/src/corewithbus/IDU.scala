package corewithbus

import chisel3._
import chisel3.util._
import essentials._

class IDU extends Module with HasInstrType { 
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new Bundle {
      val inst = UInt(32.W)
      val pc   = UInt(32.W)
    }))

    val out = Decoupled(new Bundle {
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
    })
    //EXU反馈回路
    val flush = Input(Bool())
    //regfile接口
    val rf_rs1_addr = Output(UInt(5.W))
    val rf_rs2_addr = Output(UInt(5.W))
    val rf_rs1_data = Input(UInt(32.W))
    val rf_rs2_data = Input(UInt(32.W))
  })

  val inst = io.in.bits.inst

  // --- [1] 第一层译码：大查找表 ---
  // 默认值：无效指令
  val defaultSignals = List(InstrN, FuType.alu, 0.U(7.W))
  val decodeRes = ListLookup(inst, defaultSignals, RVIInstr.table ++ Privileged.table)
  
  // 映射查找表结果
  val instrType = decodeRes(0)
  val fuType    = decodeRes(1)
  val fuOp      = decodeRes(2)

  // --- [2] 立即数提取 (由 instrType 驱动) ---
  //提取符号位
val sign_bit = inst(31)

val i_imm_raw = inst(31, 20)
val s_imm_raw = Cat(inst(31, 25), inst(11, 7))
val b_imm_raw = Cat(inst(31), inst(7), inst(30, 25), inst(11, 8))
val u_imm_raw = inst(31, 12)
val j_imm_raw = Cat(inst(31), inst(19, 12), inst(20), inst(30, 21))

// 扩充至 32 位
val final_imm = MuxLookup(instrType, 0.U)(Seq(
  // I型：12位 -> 32位 (符号扩展)
  InstrI -> Cat(Fill(20, sign_bit), i_imm_raw),
  // S型：12位 -> 32位 (符号扩展)
  InstrS -> Cat(Fill(20, sign_bit), s_imm_raw),
  // B型：12位原始段 + 末尾补0 = 13位 -> 32位 (符号扩展19位)
  InstrB -> Cat(Fill(19, sign_bit), b_imm_raw, 0.U(1.W)),
  // U型：20位原始段 + 末尾补12个0 = 32位 (无需符号扩展)
  InstrU -> Cat(u_imm_raw, 0.U(12.W)),
  // J型：20位原始段 + 末尾补0 = 21位 -> 32位 (符号扩展11位)
  InstrJ -> Cat(Fill(11, sign_bit), j_imm_raw, 0.U(1.W))
))

  // --- [3] 操作数准备 ---
  io.rf_rs1_addr := inst(19, 15)
  io.rf_rs2_addr := inst(24, 20)

  // --- [4] 精确控制信号派生 ---
  io.out.bits.isLoad   := (fuType === FuType.lsu) && LSUOpType.isLoad(fuOp)
  io.out.bits.isStore  := (fuType === FuType.lsu) && LSUOpType.isStore(fuOp)
  io.out.bits.isBranch := (fuType === FuType.alu) && (instrType === InstrB) // 或 BRUOpType 逻辑
  io.out.bits.isJump   := (instrType === InstrJ || (instrType === InstrI && fuType === FuType.bru))
  io.out.bits.rfWen    := isrfWen(instrType)
  io.out.bits.useImm   := (instrType === InstrI || 
                            instrType === InstrS || 
                            instrType === InstrU || 
                            instrType === InstrJ)

  // --- [5] 封装输出 ---
  // 1. 判断操作数 1 是否应该用 PC (针对 AUIPC, JAL)
  val src1IsPC = (instrType === InstrU && fuOp === ALUOpType.auipc) || (instrType === InstrJ) 
  // 2. 判断操作数 1 是否应该清零 (针对 LUI)
  val src1IsZero = (instrType === InstrU && fuOp === ALUOpType.lui)
  io.out.bits.src1 := MuxCase(io.rf_rs1_data, Seq(
    src1IsPC   -> io.in.bits.pc,
    src1IsZero -> 0.U(32.W)
  ))
  io.out.bits.pc     := io.in.bits.pc
  io.out.bits.imm    := final_imm
  io.out.bits.src2   := io.rf_rs2_data
  io.out.bits.fuType := fuType
  io.out.bits.fuOp   := fuOp
  io.out.bits.rdAddr := inst(11, 7)

  // 握手逻辑
  val flush = io.flush
  io.in.ready := io.out.ready
  io.out.valid := io.in.valid && !flush
}