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
  def mdu = "b010".U
  def csr = "b011".U
  //def mou = "b100".U
  def bru = "b101".U
  def apply() = UInt(log2Up(num).W)
}

object FuOpType {
  def apply() = UInt(7.W)
}

object LSUOpType {
  def lb   = "b000_0_000".U(7.W)
  def lh   = "b000_0_001".U(7.W)
  def lw   = "b000_0_010".U(7.W)
  def lbu  = "b000_0_100".U(7.W)
  def lhu  = "b000_0_101".U(7.W)
  def sb   = "b000_1_000".U(7.W)
  def sh   = "b000_1_001".U(7.W)
  def sw   = "b000_1_010".U(7.W)

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

object MDUOpType {
  // 编码策略:
  // 高3位: "b011" (接在 ALUOpType 的 auipc b010... 之后，防止冲突)
  // 第3位: "0"    (占位，对应 ALU 中的 func7 差异位)
  // 低3位: "XXX"  (完全对应 RISC-V Spec 的 funct3)

  // 乘法类 (funct3: 000 ~ 011)
  def mul    = "b011_0_000".U(7.W)
  def mulh   = "b011_0_001".U(7.W)
  def mulhsu = "b011_0_010".U(7.W)
  def mulhu  = "b011_0_011".U(7.W)

  // 除法类 (funct3: 100 ~ 111)
  def div    = "b011_0_100".U(7.W)
  def divu   = "b011_0_101".U(7.W)
  def rem    = "b011_0_110".U(7.W)
  def remu   = "b011_0_111".U(7.W)

  // 辅助判断函数
  // 观察低3位 (funct3)：
  // 0xx (0-3) 是乘法
  // 1xx (4-7) 是除法/取余
  // 所以只需要检查 bit(2) 即可快速区分
  def isDiv(op: UInt): Bool = op(2) 
  def isMul(op: UInt): Bool = !op(2)
  
  // 额外赠送：辅助判断是否是有符号取模/除法 (用于 Divider 内部逻辑)
  // div(100) 和 rem(110) 的 bit(0) 都是 0
  def isDivSign(op: UInt): Bool = !op(0) 
}
// ==================================================================
// CSR常量定义
// ==================================================================
object CSROpType {    
  def jmp  = "b1000000".U  
  def wrt  = "b0000001".U  
  def set  = "b0000011".U  
  def clr  = "b0000111".U  
}
object CSRAddress {
  val MSTATUS = 0x300.U(12.W)
  val MEPC    = 0x341.U(12.W)
  val MCAUSE  = 0x342.U(12.W)
  val MTVEC   = 0x305.U(12.W)
}
object CauseCode {
  val ENVCALL_U = 8.U(32.W)
  val ENVCALL_S = 9.U(32.W)
  val ENVCALL_M = 11.U(32.W)
  val BREAKPOINT = 3.U(32.W)
}
object PrivilegeLevel {
  val PRV_U = "b00".U(2.W)
  val PRV_S = "b01".U(2.W)
  val PRV_M = "b11".U(2.W)
}