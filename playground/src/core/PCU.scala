package core

import chisel3._
import chisel3.util._

object PCUOpType {
  def jal  		= "b1111".U
  def jalr 		= "b1110".U
  def branch  = "b0101".U
	def snpc	  = "b0100".U
	def csrjump		= "b0000".U
}
class PCU extends Module {
  val io = IO(new Bundle {
    val pc      = Input(UInt(32.W))   
    val rs1     = Input(UInt(32.W))   
    val offset  = Input(UInt(32.W))    
    val opType  = Input(UInt(4.W))    
    val dnpc    = Output(UInt(32.W))  
  })

  io.dnpc := MuxLookup(io.opType, io.pc)(Seq(
    PCUOpType.jal   -> (io.pc + io.offset),       // JAL: pc + offset
    PCUOpType.jalr  -> (io.rs1 + io.offset),     // JALR: rs1 + offset
    PCUOpType.branch -> (io.pc + io.offset),    // Branch: pc + offset
    PCUOpType.snpc -> (io.pc + 4.U),           // Normal: pc + 4
    PCUOpType.csrjump -> io.rs1         // ecall: mtvec + 0
  ))

}

