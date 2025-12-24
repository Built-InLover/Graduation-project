package core                                      
                                                     
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
                                                     
  def isrfWen(instrType : UInt): Bool = instrType(2) 
  //is regfile write enable
}                                                    

object SrcType {                                     
  def reg = "b0".U                                   
  def pc  = "b1".U                                   
  def imm = "b1".U                                   
  def apply() = UInt(1.W)                            
}                                                    
                                                     
object FuType {              
  def num = 4                                        
  def alu = "b00".U                                 
  def lsu = "b01".U                                 
  def csr = "b10".U                                 
  def bru = "b11".U                   
  def apply() = UInt(log2Up(num).W)                                
}                                                                  

object Instructions extends HasInstrType{
  def NOP = 0x00000013.U                                           
  val DecodeDefault = List(InstrN, FuType.alu, ALUOpType.add)      
  def DecodeTable = RVIInstr.table ++ Privileged.table ++ RVZicsrInstr.table
}

class Decoder extends Module with HasInstrType{
  val io = IO(new Bundle {
    val instr 		= Input(UInt(32.W))
    val instrType = Output(UInt(4.W))
    val fuType 		= Output(UInt(2.W))
    val opType 		= Output(UInt(8.W))
	val rs1       = Output(UInt(5.W)) 
    val rs2       = Output(UInt(5.W)) 
    val rd        = Output(UInt(5.W)) 
    val imm       = Output(UInt(32.W))
    val csrAddr   = Output(UInt(12.W))
  })

  val defaultDecode = Instructions.DecodeDefault.map(_.asUInt)
  val decodeResult = ListLookup(io.instr, defaultDecode, Instructions.DecodeTable)

  io.instrType := decodeResult(0)
  io.fuType    := decodeResult(1)
  io.opType    := decodeResult(2)

  val opcode = io.instr(6, 0)
  val rd     = io.instr(11, 7)
  val funct3 = io.instr(14, 12)
  val rs1    = io.instr(19, 15)
  val rs2    = io.instr(24, 20)
  val funct7 = io.instr(31, 25)
  val csr    = io.instr(31, 20) 

  val immI   = io.instr(31, 20)
  val immS   = Cat(io.instr(31, 25), io.instr(11, 7)) 
  val immB   = Cat(io.instr(31), io.instr(7), io.instr(30, 25), io.instr(11, 8), 0.U(1.W)) 
  val immU   = Cat(io.instr(31, 12), 0.U(12.W)) 
  val immJ   = Cat(io.instr(31), io.instr(19, 12), io.instr(20), io.instr(30, 21), 0.U(1.W)) 

  // CSR 指令类型判断
  val isCSR = io.fuType === FuType.csr

  io.rs1 := rs1
  io.rs2 := Mux(io.instrType === InstrR || io.instrType === InstrS || io.instrType === InstrB, rs2, 0.U)
  io.rd  := Mux(io.instrType === InstrR || io.instrType === InstrI || io.instrType === InstrU || io.instrType === InstrJ/* || isCSR*/, rd, 0.U)

  io.imm := MuxLookup(io.instrType, 0.U)(Seq(
    InstrI -> immI.asTypeOf(SInt(32.W)).asUInt,
    InstrS -> immS.asTypeOf(SInt(32.W)).asUInt,
    InstrB -> immB.asTypeOf(SInt(32.W)).asUInt,
    InstrU -> immU.asTypeOf(SInt(32.W)).asUInt,
    InstrJ -> immJ.asTypeOf(SInt(32.W)).asUInt
  ))

  // CSR 地址
  io.csrAddr := Mux(isCSR, csr, 0.U(12.W))
}
