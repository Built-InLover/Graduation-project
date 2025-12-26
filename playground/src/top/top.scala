package top

import chisel3._
import chisel3.util._
import core._

//class Memory extends Module {
//  val io = IO(Flipped(new MemoryIO())) // 注意这里要翻转
//  val a = io.writeEnable
//  val b = io.writeMask
//  val c = io.writeData
//  val d = io.readEnable
//  val e = io.addr
//
//  io.readData := a | b | c | d | e
//}
class MemoryIO extends Bundle {
  val readData    = Output(UInt(32.W))
  val readEnable  = Input(Bool())
  val addr        = Input(UInt(32.W))
  val writeEnable = Input(Bool())
  val writeMask   = Input(UInt(4.W))
  val writeData   = Input(UInt(32.W))
}

// BlackBox 用于直接引用 Verilog 实现
class Memory extends BlackBox with HasBlackBoxInline {
  val io = IO(new MemoryIO)

  setInline("Memory.v",
    """
    import "DPI-C" function bit[31:0] pmem_read(input logic[31:0] raddr);
    import "DPI-C" function void pmem_write(input logic[31:0] waddr, input logic[31:0] wdata, input byte wmask);

    module Memory(
      output reg[31:0] readData,
      input         readEnable,
      input  [31:0] addr,
      input         writeEnable,
      input  [3:0]  writeMask,
      input  [31:0] writeData
    );
      always @(*) begin
        if (readEnable) begin
          readData = pmem_read(addr);
        end else begin
          readData = 32'b0;
        end
      end

      always @(*) begin
        if (writeEnable) begin
          pmem_write(addr, writeData, {4'b0,writeMask});
        end
      end
    endmodule
    """
  )
}
class top extends Module with HasInstrType{
  val io = IO(new Bundle {
    val instr = Input(UInt(32.W))  
	val pc = Output(UInt(32.W))
  val dnpc = Output(UInt(32.W))
  })
//create unit
  val decoder = Module(new Decoder()) 
  val alu = Module(new ALU())         
  val bru = Module(new BRU())
  val pcu = Module(new PCU())
  val lsu = Module(new LSU())
  val csr = Module(new CSRU())
  val memory = Module(new Memory())
  memory.io <> lsu.io.mem  
  val regfile = Module(new RegFile())

//pc init
  val pcReg = RegInit(0x80000000L.U(32.W))
	io.pc := pcReg
  io.dnpc := pcu.io.dnpc
//decoder
  decoder.io.instr := io.instr
  val instrType = decoder.io.instrType 
  val fuType = decoder.io.fuType 
  val opType = decoder.io.opType
  val rs1 = decoder.io.rs1
  val rs2 = decoder.io.rs2
  val rd = decoder.io.rd
  val imm = decoder.io.imm
//tmp val
  val in1 = Wire(UInt(32.W))  
  val in2 = Wire(UInt(32.W))  
//  val in3 = Wire(UInt(32.W))

  val result = Wire(UInt(32.W))
  val wdata = Wire(UInt(32.W))
  val wen = Wire(Bool())

  when(instrType === InstrI || instrType === InstrS) {
    in1 := regfile.io.rdata1   
    in2 := imm   
  }.elsewhen(instrType === InstrU){
    //instr(5) diffs from lui and auipc 
    //LUI    = "b0110111"
    //AUIPC  = "b0010111"
    in1 := Mux(io.instr(5), 0.U, pcReg)
    in2 := imm
  }.otherwise {
    in1 := regfile.io.rdata1   
    in2 := regfile.io.rdata2   
  }
//csr
  val trap = csr.io.trap
  csr.io.csrOpType := opType
  csr.io.csrCmd.valid := (fuType === FuType.csr) && (csr.io.csrOpType =/= CSROpType.jmp)
  csr.io.csrCmd.addr := decoder.io.csrAddr
  csr.io.csrCmd.rs1 := in1 
  
  csr.io.privOp.mret := (fuType === FuType.csr) && (csr.io.csrOpType === CSROpType.jmp) && io.instr(21)
  csr.io.privOp.ecall := (fuType === FuType.csr) && (csr.io.csrOpType === CSROpType.jmp) && !io.instr(21)
  csr.io.privOp.pc := pcReg

//regfile
  regfile.io.rs1 := rs1
  regfile.io.rs2 := rs2
  regfile.io.rd := rd
  regfile.io.wen := wen
  regfile.io.wdata := wdata

  when(fuType === FuType.alu){
    wdata := result
    wen := true.B
  }.elsewhen(fuType === FuType.lsu && LSUOpType.isLoad(opType)){
    wdata := lsu.io.rdata
    wen := true.B
  }.elsewhen(fuType === FuType.bru && (bru.io.opType === BRUOpType.jal || bru.io.opType === BRUOpType.jalr)){
    wdata := pcReg + 4.U //need to be replaced
    wen := true.B
  }.elsewhen(fuType === FuType.csr && (csr.io.csrOpType =/= CSROpType.jmp)){
    wdata := csr.io.rdata
    wen := true.B
  }.otherwise{
    wen := false.B
    wdata := 0.U
  }
//alu
  alu.io.in1 := in1
  alu.io.in2 := in2
//bru
  bru.io.rs1 := in1
  bru.io.rs2 := in2
  bru.io.opType := opType

//result --- temp value
  when(fuType === FuType.alu) {
    alu.io.opType := opType
    result := alu.io.result
  }.elsewhen(fuType === FuType.lsu){
    alu.io.opType := ALUOpType.add
    result := alu.io.result
  }.elsewhen(fuType === FuType.bru) {
    alu.io.opType := opType
    result := bru.io.branchTaken
  }.otherwise {
    alu.io.opType := opType
    result := 0.U
  }

//lsu
  when(fuType === FuType.lsu && LSUOpType.isStore(opType)){
    lsu.io.addr := result
    lsu.io.dataIn := regfile.io.rdata2
    lsu.io.mask := LSUOpType.mask(opType)
    lsu.io.sign := LSUOpType.isSigned(opType)
    lsu.io.wen := 1.U
    lsu.io.ren := 0.U
  }.elsewhen(fuType === FuType.lsu && LSUOpType.isLoad(opType)){
    lsu.io.addr := result
    lsu.io.dataIn := 0.U
    lsu.io.mask := LSUOpType.mask(opType)
    lsu.io.sign := LSUOpType.isSigned(opType)
    lsu.io.wen := 0.U
    lsu.io.ren := 1.U
  }.otherwise{
    lsu.io.addr := result
    lsu.io.dataIn := 0.U 
    lsu.io.mask := LSUOpType.mask(opType)
    lsu.io.sign := LSUOpType.isSigned(opType)
    lsu.io.wen := 0.U
    lsu.io.ren := 0.U
  }
//pcu  
  when(fuType === FuType.bru && result === 1.U){
    when(bru.io.opType === BRUOpType.jal){
      pcu.io.opType := PCUOpType.jal
      pcu.io.pc := pcReg     
      pcu.io.rs1 := in1      
      pcu.io.offset := imm
    }.elsewhen(bru.io.opType === BRUOpType.jalr){
      pcu.io.opType := PCUOpType.jalr
      pcu.io.pc := pcReg     
      pcu.io.rs1 := in1      
      pcu.io.offset := imm  
    }.otherwise{
      pcu.io.opType := PCUOpType.branch
      pcu.io.pc := pcReg
      pcu.io.rs1 := in1
      pcu.io.offset := imm
    }
  }.elsewhen(fuType === FuType.csr && csr.io.csrOpType === CSROpType.jmp){
    pcu.io.opType := PCUOpType.csrjump
    pcu.io.pc := pcReg
    pcu.io.rs1 := csr.io.dnpc
    pcu.io.offset := 0.U
  }.otherwise {
    pcu.io.opType := PCUOpType.snpc
    pcu.io.pc := pcReg
    pcu.io.rs1 := 0.U
    pcu.io.offset := 0.U
  }

  pcReg := pcu.io.dnpc
}
