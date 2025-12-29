package corewithbus

import chisel3._
import chisel3.util._
import common._

class MemBlackBox extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock     = Input(Clock())
    // IFU 端口
    val ifu_addr  = Input(UInt(32.W))
    val ifu_en    = Input(Bool())     // 新增：取指使能
    val ifu_data  = Output(UInt(32.W))
    // LSU 端口
    val lsu_addr  = Input(UInt(32.W))
    val lsu_en    = Input(Bool())     // 新增：访存使能 (Valid && Ready)
    val lsu_wen   = Input(Bool())
    val lsu_wmask = Input(UInt(4.W))
    val lsu_wdata = Input(UInt(32.W))
    val lsu_rdata = Output(UInt(32.W))
  })

  setInline("MemBlackBox.sv",
    """
      |module MemBlackBox(
      |    input clock,
      |    input [31:0] ifu_addr,
      |    input ifu_en,
      |    output reg [31:0] ifu_data,
      |    input [31:0] lsu_addr,
      |    input lsu_en,
      |    input lsu_wen,
      |    input [3:0] lsu_wmask,
      |    input [31:0] lsu_wdata,
      |    output reg [31:0] lsu_rdata
      |);
      |    import "DPI-C" function int pmem_read(input int addr);
      |    import "DPI-C" function void pmem_write(input int addr, input int data, input byte mask);
      |
      |    always @(posedge clock) begin
      |        // 1. 只有在使能（握手成功）时才读内存更新寄存器
      |        if (ifu_en) begin
      |            ifu_data <= pmem_read(ifu_addr);
      |        end
      |
      |        // 2. LSU 端口同样增加使能判定
      |        if (lsu_en) begin
      |            if (lsu_wen) begin
      |                pmem_write(lsu_addr, lsu_wdata, {4'b0, lsu_wmask});
      |                lsu_rdata <= 32'b0;
      |            end else begin
      |                lsu_rdata <= pmem_read(lsu_addr);
      |            end
      |        end
      |    end
      |endmodule
    """.stripMargin)
}

class MemSystem extends Module {
  val io = IO(new Bundle {
    val ifu_bus = Flipped(new SimpleBus())
    val lsu_bus = Flipped(new SimpleBus())
  })

  val bb = Module(new MemBlackBox())
  bb.io.clock := clock

  // --- IFU 逻辑 ---
  bb.io.ifu_addr := io.ifu_bus.req.bits.addr
  // 关键：只有握手发生 (fire) 时才让 BlackBox 动弹
  bb.io.ifu_en   := io.ifu_bus.req.fire 
  io.ifu_bus.req.ready := true.B 
  
  io.ifu_bus.resp.valid      := RegNext(io.ifu_bus.req.fire, false.B)
  io.ifu_bus.resp.bits.rdata := bb.io.ifu_data

  // --- LSU 逻辑 ---
  bb.io.lsu_addr  := io.lsu_bus.req.bits.addr
  bb.io.lsu_wdata := io.lsu_bus.req.bits.wdata
  bb.io.lsu_wmask := io.lsu_bus.req.bits.wmask
  bb.io.lsu_wen   := io.lsu_bus.req.bits.wen
  
  // 关键：LSU 使能也绑定到握手上
  bb.io.lsu_en    := io.lsu_bus.req.fire
  io.lsu_bus.req.ready := true.B

  io.lsu_bus.resp.valid      := RegNext(io.lsu_bus.req.fire, false.B)
  io.lsu_bus.resp.bits.rdata := bb.io.lsu_rdata
}
