package corewithbus

import chisel3._
import chisel3.util._
import common._

class MemBlackBox extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    // 端口 A：专门给 IFU
    val ifu_addr  = Input(UInt(32.W))
    val ifu_data  = Output(UInt(32.W))
    
    // 端口 B：专门给 LSU
    val lsu_addr  = Input(UInt(32.W))
    val lsu_wen   = Input(Bool())
    val lsu_wmask = Input(UInt(4.W))
    val lsu_wdata = Input(UInt(32.W))
    val lsu_rdata = Output(UInt(32.W))
  })

  // 这里的 SV 代码使用了时钟触发写，组合逻辑读
  setInline("MemBlackBox.sv",
    """
      |module MemBlackBox(
      |    input clock,
      |    input [31:0] ifu_addr,
      |    output [31:0] ifu_data,
      |    input [31:0] lsu_addr,
      |    input lsu_wen,
      |    input [3:0] lsu_wmask,
      |    input [31:0] lsu_wdata,
      |    output [31:0] lsu_rdata
      |);
      |    import "DPI-C" function int pmem_read(input int addr);
      |    import "DPI-C" function void pmem_write(input int addr, input int data, input byte mask);
      |
      |    // 读操作：组合逻辑立刻返回 (用于仿真)
      |    assign ifu_data  = pmem_read(ifu_addr);
      |    assign lsu_rdata = pmem_read(lsu_addr);
      |
      |    // 写操作：必须随波逐流（时钟上升沿），否则仿真会乱
      |    always @(posedge clock) begin
      |        if (lsu_wen) begin
      |            pmem_write(lsu_addr, lsu_wdata, {4'b0, lsu_wmask});
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

  // --- IFU 端口连接 ---
  bb.io.ifu_addr := io.ifu_bus.req.bits.addr
  io.ifu_bus.req.ready := true.B // 仿真内存总是准备好的
  
  // 模拟同步读时序：请求后的下一拍返回 Valid
  io.ifu_bus.resp.valid := RegNext(io.ifu_bus.req.fire, false.B)
  io.ifu_bus.resp.bits.rdata := bb.io.ifu_data

  // --- LSU 端口连接 ---
  bb.io.lsu_addr  := io.lsu_bus.req.bits.addr
  bb.io.lsu_wen   := io.lsu_bus.req.bits.wen && io.lsu_bus.req.valid
  bb.io.lsu_wmask := io.lsu_bus.req.bits.wmask
  bb.io.lsu_wdata := io.lsu_bus.req.bits.wdata
  io.lsu_bus.req.ready := true.B

  io.lsu_bus.resp.valid := RegNext(io.lsu_bus.req.fire, false.B)
  io.lsu_bus.resp.bits.rdata := bb.io.lsu_rdata
}
