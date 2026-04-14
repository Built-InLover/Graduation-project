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
      |`ifdef SIMULATION
      |  import "DPI-C" function void sim_itrace(input int pc, input int dnpc);
      |  always @(posedge clock) begin
      |    if (enable) sim_itrace(pc, dnpc);
      |  end
      |`endif
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
      |`ifdef SIMULATION
      |  import "DPI-C" function void sim_mtrace(input int pc, input int addr, input int data, input byte is_write, input int size);
      |  always @(posedge clock) begin
      |    if (enable) sim_mtrace(pc, addr, data, {7'b0, is_write}, {30'b0, size});
      |  end
      |`endif
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
      |`ifdef SIMULATION
      |  import "DPI-C" function void sim_regtrace(input int pc, input int rd, input int wdata);
      |  always @(posedge clock) begin
      |    if (enable) sim_regtrace(pc, {27'b0, rd}, wdata);
      |  end
      |`endif
      |endmodule
      |""".stripMargin)
}

class SimDifftest extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock  = Input(Clock())
    val enable = Input(Bool())
    val pc     = Input(UInt(32.W))
    val dnpc   = Input(UInt(32.W))
    val regs   = Input(Vec(32, UInt(32.W)))
    val csrs   = Input(Vec(4, UInt(32.W)))
  })

  private val regPortDecls = (0 until 32).map(i => s"  input [31:0] regs_$i").mkString(",\n")
  private val csrPortDecls = (0 until 4).map(i => s"  input [31:0] csrs_$i").mkString(",\n")
  private val gprSetCalls  = (0 until 32).map(i => s"      sim_set_gpr($i, regs_$i);").mkString("\n")

  setInline("SimDifftest.v",
    "module SimDifftest(\n" +
    "  input        clock,\n" +
    "  input        enable,\n" +
    "  input [31:0] pc,\n" +
    "  input [31:0] dnpc,\n" +
    regPortDecls + ",\n" +
    csrPortDecls + "\n" +
    ");\n" +
    "`ifdef SIMULATION\n" +
    "  import \"DPI-C\" function void sim_set_gpr(input int idx, input int value);\n" +
    "  import \"DPI-C\" function void sim_difftest(input int pc, input int dnpc, input int mcause, input int mepc, input int mstatus, input int mtvec);\n" +
    "  always @(posedge clock) begin\n" +
    "    if (enable) begin\n" +
    gprSetCalls + "\n" +
    "      sim_difftest(pc, dnpc, csrs_0, csrs_1, csrs_2, csrs_3);\n" +
    "    end\n" +
    "  end\n" +
    "`endif\n" +
    "endmodule\n"
  )
}

class SimPerfCounters extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock          = Input(Clock())
    val reset          = Input(Bool())
    val ifu_fire       = Input(Bool())
    val idu_fire       = Input(Bool())
    val icache_hit     = Input(Bool())
    val icache_miss    = Input(Bool())
    val user_mode      = Input(Bool())
    val commit_valid   = Input(Bool())
    val commit_fuType  = Input(UInt(3.W))
  })

  setInline("SimPerfCounters.v",
    """module SimPerfCounters(
      |  input       clock,
      |  input       reset,
      |  input       ifu_fire,
      |  input       idu_fire,
      |  input       icache_hit,
      |  input       icache_miss,
      |  input       user_mode,
      |  input       commit_valid,
      |  input [2:0] commit_fuType
      |);
      |`ifdef SIMULATION
      |  import "DPI-C" function void sim_perf_event(
      |    input byte ifu_fire,
      |    input byte idu_fire,
      |    input byte icache_hit,
      |    input byte icache_miss,
      |    input byte user_mode,
      |    input byte commit_valid,
      |    input byte commit_fuType
      |  );
      |  always @(posedge clock) begin
      |    if (!reset) begin
      |      sim_perf_event(
      |        {7'b0, ifu_fire},
      |        {7'b0, idu_fire},
      |        {7'b0, icache_hit},
      |        {7'b0, icache_miss},
      |        {7'b0, user_mode},
      |        {7'b0, commit_valid},
      |        {5'b0, commit_fuType}
      |      );
      |    end
      |  end
      |`endif
      |endmodule
      |""".stripMargin)
}