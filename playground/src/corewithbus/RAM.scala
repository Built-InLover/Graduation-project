package corewithbus

import chisel3._
import chisel3.util._
import common._

class MemBlackBox extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock     = Input(Clock())
    
    // IFU 端口
    val ifu_addr  = Input(UInt(32.W))
    val ifu_en    = Input(Bool())
    val ifu_data  = Output(UInt(32.W))
    
    // LSU 端口
    val lsu_addr  = Input(UInt(32.W))
    val lsu_en    = Input(Bool())
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
      |    
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
      |        // IFU 读取
      |        if (ifu_en) begin
      |            ifu_data <= pmem_read(ifu_addr);
      |        end
      |
      |        // LSU 读写
      |        if (lsu_en) begin
      |            if (lsu_wen) begin
      |                pmem_write(lsu_addr, lsu_wdata, {4'b0, lsu_wmask});
      |                // 写操作不需要读数据，但为了波形好看可以清零
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
    val ifu_bus = Flipped(new AXI4LiteInterface(AXI4LiteParams(32, 32)))
    val lsu_bus = Flipped(new AXI4LiteInterface(AXI4LiteParams(32, 32)))
  })

  val bb = Module(new MemBlackBox())
  bb.io.clock := clock

  // ==================================================================
  //                        1. IFU 通道 (纯读)
  // ==================================================================
  // Tie off unused slave channels
  io.ifu_bus.aw.ready := false.B
  io.ifu_bus.w.ready  := false.B
  io.ifu_bus.b.valid  := false.B
  io.ifu_bus.b.bits   := DontCare

  val ifu_r_q = Module(new Queue(chiselTypeOf(io.ifu_bus.r.bits), 4, pipe = true))
  
  io.ifu_bus.ar.ready := ifu_r_q.io.enq.ready
  val ifu_fire = io.ifu_bus.ar.fire

  bb.io.ifu_addr := io.ifu_bus.ar.bits.addr
  bb.io.ifu_en   := ifu_fire

  ifu_r_q.io.enq.valid     := RegNext(ifu_fire, false.B)
  ifu_r_q.io.enq.bits.data := bb.io.ifu_data
  ifu_r_q.io.enq.bits.resp := "b00".U // OKAY
  
  io.ifu_bus.r <> ifu_r_q.io.deq

  // ==================================================================
  //                        2. LSU 通道 (读写+仲裁)
  // ==================================================================
  // 分离两个队列：R 用来传读数据，B 用来传写完成响应
  val lsu_r_q = Module(new Queue(chiselTypeOf(io.lsu_bus.r.bits), 4, pipe = true))
  val lsu_b_q = Module(new Queue(chiselTypeOf(io.lsu_bus.b.bits), 4, pipe = true))

  val can_read  = lsu_r_q.io.enq.ready
  val can_write = lsu_b_q.io.enq.ready

  // 仲裁：如果 AXI 同时发来 AR(读) 和 AW+W(写)，优先处理读
  val do_read  = io.lsu_bus.ar.valid && can_read
  val do_write = !do_read && (io.lsu_bus.aw.valid && io.lsu_bus.w.valid) && can_write

  io.lsu_bus.ar.ready := can_read
  // 只有真正执行 write 时，才拉高 AW 和 W 的 ready，防止错吃信号
  io.lsu_bus.aw.ready := do_write
  io.lsu_bus.w.ready  := do_write

  bb.io.lsu_en    := do_read || do_write
  bb.io.lsu_wen   := do_write
  bb.io.lsu_addr  := Mux(do_read, io.lsu_bus.ar.bits.addr, io.lsu_bus.aw.bits.addr)
  bb.io.lsu_wdata := io.lsu_bus.w.bits.data
  bb.io.lsu_wmask := io.lsu_bus.w.bits.strb

  // 分别推入对应的响应队列
  lsu_r_q.io.enq.valid     := RegNext(do_read, false.B)
  lsu_r_q.io.enq.bits.data := bb.io.lsu_rdata
  lsu_r_q.io.enq.bits.resp := "b00".U

  lsu_b_q.io.enq.valid     := RegNext(do_write, false.B)
  lsu_b_q.io.enq.bits.resp := "b00".U

  io.lsu_bus.r <> lsu_r_q.io.deq
  io.lsu_bus.b <> lsu_b_q.io.deq
}