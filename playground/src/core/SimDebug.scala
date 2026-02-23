package core

import chisel3._
import chisel3.util.HasBlackBoxInline

class SimItrace extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock  = Input(Clock())
    val enable = Input(Bool())
    val pc     = Input(UInt(32.W))
    val dnpc   = Input(UInt(32.W))
  })

  setInline("SimItrace.v",
    """module SimItrace(
      |  input        clock,
      |  input        enable,
      |  input [31:0] pc,
      |  input [31:0] dnpc
      |);
      |  import "DPI-C" function void sim_itrace(input int pc, input int dnpc);
      |  always @(posedge clock) begin
      |    if (enable) sim_itrace(pc, dnpc);
      |  end
      |endmodule
      |""".stripMargin)
}

class SimMtrace extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock    = Input(Clock())
    val enable   = Input(Bool())
    val pc       = Input(UInt(32.W))
    val addr     = Input(UInt(32.W))
    val data     = Input(UInt(32.W))
    val is_write = Input(Bool())
    val size     = Input(UInt(2.W))
  })

  setInline("SimMtrace.v",
    """module SimMtrace(
      |  input        clock,
      |  input        enable,
      |  input [31:0] pc,
      |  input [31:0] addr,
      |  input [31:0] data,
      |  input        is_write,
      |  input [1:0]  size
      |);
      |  import "DPI-C" function void sim_mtrace(input int pc, input int addr, input int data, input byte is_write, input int size);
      |  always @(posedge clock) begin
      |    if (enable) sim_mtrace(pc, addr, data, {7'b0, is_write}, {30'b0, size});
      |  end
      |endmodule
      |""".stripMargin)
}

class SimRegtrace extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock  = Input(Clock())
    val enable = Input(Bool())
    val pc     = Input(UInt(32.W))
    val rd     = Input(UInt(5.W))
    val wdata  = Input(UInt(32.W))
  })

  setInline("SimRegtrace.v",
    """module SimRegtrace(
      |  input        clock,
      |  input        enable,
      |  input [31:0] pc,
      |  input [4:0]  rd,
      |  input [31:0] wdata
      |);
      |  import "DPI-C" function void sim_regtrace(input int pc, input int rd, input int wdata);
      |  always @(posedge clock) begin
      |    if (enable) sim_regtrace(pc, {27'b0, rd}, wdata);
      |  end
      |endmodule
      |""".stripMargin)
}
