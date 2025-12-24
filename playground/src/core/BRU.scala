package core

import chisel3._
import chisel3.util._

object BRUOpType {
// less equal great signed_if/(jal or jalr)
	def jal  = "b1111".U
  def jalr = "b1110".U
  def beq  = "b0101".U
  def bne  = "b1011".U
  def blt  = "b1001".U
  def bge  = "b0111".U
  def bltu = "b1000".U
  def bgeu = "b0110".U
}

class BRU extends Module {
  val io = IO(new Bundle {
    val rs1 = Input(UInt(32.W))  
    val rs2 = Input(UInt(32.W))  
    val opType = Input(UInt(4.W))
    val branchTaken = Output(Bool()) 
  })

  val diff = io.rs1 - io.rs2
	val overflow = (io.rs1(31) =/= io.rs2(31)) && (diff(31) =/= io.rs1(31))

  val isEqual = diff === 0.U
  val isLessThan = diff(31) === 1.U  
	
	val signedLessThan = Mux(overflow, io.rs1(31), isLessThan)

  val isUnsignedLessThan = io.rs1 < io.rs2

  io.branchTaken := MuxLookup(io.opType, false.B)(Seq(
    BRUOpType.jal  -> true.B,     
    BRUOpType.jalr -> true.B,     
    BRUOpType.beq  -> isEqual,    
    BRUOpType.bne  -> !isEqual,   
    BRUOpType.blt  -> signedLessThan, 
    BRUOpType.bge  -> !signedLessThan,
    BRUOpType.bltu -> isUnsignedLessThan,
    BRUOpType.bgeu -> !isUnsignedLessThan
  ))
}
