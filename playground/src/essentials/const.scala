package essentials

import chisel3._
import chisel3.util._

trait HasInstrType {
  def InstrN  = "b0000".U
  def InstrI  = "b0100".U
  def InstrR  = "b0101".U
  def InstrS  = "b0010".U
  def InstrB  = "b0001".U
  def InstrU  = "b0110".U
  def InstrJ  = "b0111".U
  //def InstrA  = "b1110".U
  //def InstrSA = "b1111".U // Atom Inst: SC

  def isrfWen(instrType : UInt): Bool = instrType(2)
}

object SrcType {
  def reg = "b0".U
  def pc  = "b1".U
  def imm = "b1".U
  def apply() = UInt(1.W)
}

object FuType {
  def num = 5
  def alu = "b000".U
  def lsu = "b001".U
  //def mdu = "b010".U
  def csr = "b011".U
  //def mou = "b100".U
  def bru = alu
  def apply() = UInt(log2Up(num).W)
}

object FuOpType {
  def apply() = UInt(7.W)
}

object LSUOpType {
  def lb   = "b0000000".U
  def lh   = "b0000001".U
  def lw   = "b0000010".U
  def lbu  = "b0000100".U
  def lhu  = "b0000101".U
  def sb   = "b0001000".U
  def sh   = "b0001001".U
  def sw   = "b0001010".U

  def isStore(func: UInt): Bool = func(3)
  def isLoad(func: UInt): Bool = !isStore(func)  
  def isSigned(func: UInt): Bool = !func(2)
  def mask(func: UInt): UInt = MuxLookup(func(1, 0), "b0000".U)(Seq(
  "b00".U -> "b0001".U,  // Byte (b)
  "b01".U -> "b0011".U,  // Halfword (h)
  "b10".U -> "b1111".U   // Word (w)
))
}

object ALUOpType {
  // 格式: 000 (高位补零) | func7(5) | func3
  def add  = "b000_0_000".U(7.W)
  def sub  = "b000_1_000".U(7.W)
  def sll  = "b000_0_001".U(7.W)
  def slt  = "b000_0_010".U(7.W)
  def sltu = "b000_0_011".U(7.W)
  def xor  = "b000_0_100".U(7.W)
  def srl  = "b000_0_101".U(7.W)
  def sra  = "b000_1_101".U(7.W)
  def or   = "b000_0_110".U(7.W)
  def and  = "b000_0_111".U(7.W)

  def lui   = "b001_0_000".U(7.W) // 随便选，只要前三位不是 000-111 里的冲突项
  def auipc = "b010_0_000".U(7.W)

  // 快捷工具函数
  def isSub(op: UInt): Bool = op(3)
  def isSra(op: UInt): Bool = op(3)
}

object BRUOpType {
  // 格式: 000 (高位补零) | Type(J:1, B:0) | func3
  def jal   = "b000_1_000".U(7.W)
  def jalr  = "b000_1_001".U(7.W)
  
  // Branch 指令：Bit(3) 为 0，低三位完全对应 RISC-V 规范的 func3
  def beq   = "b000_0_000".U(7.W) // func3: 000
  def bne   = "b000_0_001".U(7.W) // func3: 001
  def blt   = "b000_0_100".U(7.W) // func3: 100
  def bge   = "b000_0_101".U(7.W) // func3: 101
  def bltu  = "b000_0_110".U(7.W) // func3: 110
  def bgeu  = "b000_0_111".U(7.W) // func3: 111
}