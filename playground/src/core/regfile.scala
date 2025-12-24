package core

import chisel3._
import chisel3.util._

class RegFile extends Module {
  val io = IO(new Bundle {
    val rs1     = Input(UInt(5.W))  // 读寄存器1索引
    val rs2     = Input(UInt(5.W))  // 读寄存器2索引
    val rd      = Input(UInt(5.W))  // 写寄存器索引
    val wen     = Input(Bool())     // 写使能信号
    val wdata   = Input(UInt(32.W)) // 写入数据
    val rdata1  = Output(UInt(32.W)) // 读出数据1
    val rdata2  = Output(UInt(32.W)) // 读出数据2
  })
	val regs = RegInit(VecInit(0.U(32.W) +: Seq.fill(31)(0.U(32.W)))) 
	
	io.rdata1 := regs(io.rs1) 
	io.rdata2 := regs(io.rs2)
	
	when(io.wen && io.rd =/= 0.U) {
	  regs(io.rd) := io.wdata
	}
	
	// 确保 x0 永远是 0（避免被优化）
	regs(0) := 0.U
	dontTouch(regs(0))
    val dpiRegFile = Module(new DPIModule)
    dpiRegFile.io.regs := regs
}
class DPIModule extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val regs = Input(Vec(32, UInt(32.W)))
  })

  // 在这里直接生成 Verilog 中需要的 DPI-C 代码
setInline("DPIModule.sv",
  """|
     |import "DPI-C" function void read_reg(input logic [31:0] signal[31:0]);
     |
     |module DPIModule(
     |  input logic [31:0] regs_0,  input logic [31:0] regs_1,
     |  input logic [31:0] regs_2,  input logic [31:0] regs_3,
     |  input logic [31:0] regs_4,  input logic [31:0] regs_5,
     |  input logic [31:0] regs_6,  input logic [31:0] regs_7,
     |  input logic [31:0] regs_8,  input logic [31:0] regs_9,
     |  input logic [31:0] regs_10, input logic [31:0] regs_11,
     |  input logic [31:0] regs_12, input logic [31:0] regs_13,
     |  input logic [31:0] regs_14, input logic [31:0] regs_15,
     |  input logic [31:0] regs_16, input logic [31:0] regs_17,
     |  input logic [31:0] regs_18, input logic [31:0] regs_19,
     |  input logic [31:0] regs_20, input logic [31:0] regs_21,
     |  input logic [31:0] regs_22, input logic [31:0] regs_23,
     |  input logic [31:0] regs_24, input logic [31:0] regs_25,
     |  input logic [31:0] regs_26, input logic [31:0] regs_27,
     |  input logic [31:0] regs_28, input logic [31:0] regs_29,
     |  input logic [31:0] regs_30, input logic [31:0] regs_31
     |);
     |
     |  logic [31:0] regs[32];
     |
     |  always_comb begin
     |    regs[0]  = regs_0;  regs[1]  = regs_1;  regs[2]  = regs_2;  regs[3]  = regs_3;
     |    regs[4]  = regs_4;  regs[5]  = regs_5;  regs[6]  = regs_6;  regs[7]  = regs_7;
     |    regs[8]  = regs_8;  regs[9]  = regs_9;  regs[10] = regs_10; regs[11] = regs_11;
     |    regs[12] = regs_12; regs[13] = regs_13; regs[14] = regs_14; regs[15] = regs_15;
     |    regs[16] = regs_16; regs[17] = regs_17; regs[18] = regs_18; regs[19] = regs_19;
     |    regs[20] = regs_20; regs[21] = regs_21; regs[22] = regs_22; regs[23] = regs_23;
     |    regs[24] = regs_24; regs[25] = regs_25; regs[26] = regs_26; regs[27] = regs_27;
     |    regs[28] = regs_28; regs[29] = regs_29; regs[30] = regs_30; regs[31] = regs_31;
     |
     |    read_reg(regs);
     |  end
     |endmodule
     |""".stripMargin
)

}
