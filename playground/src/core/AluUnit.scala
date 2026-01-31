package core

import chisel3._
import chisel3.util._
import essentials._

class AluUnit extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new Bundle {
      val src1     = UInt(32.W)
      val src2     = UInt(32.W)
      val imm      = UInt(32.W)
      val pc       = UInt(32.W)
      val fuOp     = FuOpType()
      val useImm   = Bool()
      val isBranch = Bool()
      val isJump   = Bool()
      val isMem    = Bool() 
    }))

    val out = Decoupled(new Bundle {
      val data     = UInt(32.W)
      val dnpc     = UInt(32.W)
      val targetPC = UInt(32.W)
      val taken    = Bool()
    })
  })

  val src1 = io.in.bits.src1
  val src2 = io.in.bits.src2
  val imm  = io.in.bits.imm
  val pc   = io.in.bits.pc
  val fuOp = io.in.bits.fuOp
  val isMem = io.in.bits.isMem

  // --- 操作数准备 ---
  val aluIn2 = Mux(io.in.bits.useImm, imm, src2)
  val shamt  = aluIn2(4, 0)

  // --- ALU 核心计算 ---
  val aluLogicRes = MuxLookup(fuOp(2, 0), 0.U)(Seq(
      0.U -> Mux(ALUOpType.isSub(fuOp), src1 - aluIn2, src1 + aluIn2),
      1.U -> (src1 << shamt),
      2.U -> (src1.asSInt < aluIn2.asSInt).asUInt,
      3.U -> (src1 < aluIn2).asUInt,
      4.U -> (src1 ^ aluIn2),
      5.U -> Mux(ALUOpType.isSra(fuOp), (src1.asSInt >> shamt).asUInt, src1 >> shamt),
      6.U -> (src1 | aluIn2),
      7.U -> (src1 & aluIn2)
  ))
  
  // 如果是 Mem，直接算加法；否则走 ALU 逻辑
  // 这里的 + 号会自动处理 src1 + imm (因为 aluIn2 已经是 imm 了)
  val aluResult = Mux(isMem, src1 + aluIn2, aluLogicRes)

  // --- 分支判断 ---
  val compRes = MuxLookup(fuOp(2, 0), false.B)(Seq(
    0.U -> (src1 === src2),
    1.U -> (src1 =/= src2),
    4.U -> (src1.asSInt < src2.asSInt),
    5.U -> (src1.asSInt >= src2.asSInt),
    6.U -> (src1 < src2),
    7.U -> (src1 >= src2)
  ))
  val branchTake = (io.in.bits.isBranch && compRes) || io.in.bits.isJump

  // --- DNPC 计算 ---
  val basePC   = Mux(fuOp === BRUOpType.jalr, src1, pc)
  val targetPC = (basePC + imm) & (~1.U(32.W))
  val dnpc     = Mux(branchTake, targetPC, pc + 4.U)

  // --- 输出 ---
  io.in.ready  := io.out.ready
  io.out.valid := io.in.valid

  io.out.bits.data     := aluResult // 现在 Mem 指令也能算出正确的地址了
  io.out.bits.dnpc     := dnpc
  io.out.bits.targetPC := targetPC
  io.out.bits.taken    := branchTake
}