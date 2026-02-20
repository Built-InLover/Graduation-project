package core

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR // [新增] 引入硬件随机数发生器
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
  //                        🔥 混沌引擎 (Chaos Engine) 🔥
  // ==================================================================
  // 生成一个 16 位的伪随机数，每个时钟周期都在变
  val chaos = LFSR(16)
  
  // 定义每个通道的放行概率 (截取不同位，互相独立)
  // 例如：chaos(7, 0) 范围是 0~255。如果 > 128，就是约 50% 的概率放行
  val allow_ifu_ar = chaos(3, 0)   > 4.U  // 约 75% 概率允许取指
  val allow_ifu_r  = chaos(7, 4)   > 8.U  // 约 50% 概率返回指令
  
  val allow_lsu_ar = chaos(11, 8)  > 10.U // 约 30% 概率允许 Load (模拟内存忙)
  val allow_lsu_aw = chaos(15, 12) > 8.U  // 约 50% 概率允许 Store 地址
  val allow_lsu_w  = chaos(3, 0)   > 8.U  // 约 50% 概率允许 Store 数据
  val allow_lsu_b  = chaos(7, 4)   > 10.U // 约 30% 概率返回写响应
  val allow_lsu_r  = chaos(11, 8)  > 8.U  // 约 50% 概率返回读数据

  // ==================================================================
  //                        1. IFU 通道 (带随机延迟)
  // ==================================================================
  io.ifu_bus.aw.ready := false.B
  io.ifu_bus.w.ready  := false.B
  io.ifu_bus.b.valid  := false.B
  io.ifu_bus.b.bits   := DontCare

  val ifu_r_q = Module(new Queue(chiselTypeOf(io.ifu_bus.r.bits), 4, pipe = true))

  // [修改] 只有队列有空位，且随机数允许时，才拉高 ready
  io.ifu_bus.ar.ready := ifu_r_q.io.enq.ready && allow_ifu_ar
  val ifu_fire = io.ifu_bus.ar.valid && io.ifu_bus.ar.ready

  bb.io.ifu_addr := io.ifu_bus.ar.bits.addr
  bb.io.ifu_en   := ifu_fire

  ifu_r_q.io.enq.valid     := RegNext(ifu_fire, false.B)
  ifu_r_q.io.enq.bits.data := bb.io.ifu_data
  ifu_r_q.io.enq.bits.resp := "b00".U
  
  // [修改] R 通道随机延迟返回给 CPU
  io.ifu_bus.r.valid := ifu_r_q.io.deq.valid && allow_ifu_r
  io.ifu_bus.r.bits  := ifu_r_q.io.deq.bits
  ifu_r_q.io.deq.ready := io.ifu_bus.r.ready && allow_ifu_r


  // ==================================================================
  //                        2. LSU 通道 (带随机延迟)
  // ==================================================================
  val lsu_r_q = Module(new Queue(chiselTypeOf(io.lsu_bus.r.bits), 4, pipe = true))
  val lsu_b_q = Module(new Queue(chiselTypeOf(io.lsu_bus.b.bits), 4, pipe = true))

  // 基础的读写就绪条件
  val can_read  = lsu_r_q.io.enq.ready && allow_lsu_ar
  val can_write = lsu_b_q.io.enq.ready && allow_lsu_aw && allow_lsu_w // 模拟双通道同时 Ready

  // 读写仲裁
  val do_read  = io.lsu_bus.ar.valid && can_read
  val do_write = !do_read && (io.lsu_bus.aw.valid && io.lsu_bus.w.valid) && can_write

  io.lsu_bus.ar.ready := can_read
  io.lsu_bus.aw.ready := do_write 
  io.lsu_bus.w.ready  := do_write

  bb.io.lsu_en    := do_read || do_write
  bb.io.lsu_wen   := do_write
  bb.io.lsu_addr  := Mux(do_read, io.lsu_bus.ar.bits.addr, io.lsu_bus.aw.bits.addr)
  bb.io.lsu_wdata := io.lsu_bus.w.bits.data
  bb.io.lsu_wmask := io.lsu_bus.w.bits.strb

  // 写入队列
  lsu_r_q.io.enq.valid     := RegNext(do_read, false.B)
  lsu_r_q.io.enq.bits.data := bb.io.lsu_rdata
  lsu_r_q.io.enq.bits.resp := "b00".U

  lsu_b_q.io.enq.valid     := RegNext(do_write, false.B)
  lsu_b_q.io.enq.bits.resp := "b00".U

  // [修改] 响应通道随机延迟返回
  io.lsu_bus.r.valid   := lsu_r_q.io.deq.valid && allow_lsu_r
  io.lsu_bus.r.bits    := lsu_r_q.io.deq.bits
  lsu_r_q.io.deq.ready := io.lsu_bus.r.ready && allow_lsu_r

  io.lsu_bus.b.valid   := lsu_b_q.io.deq.valid && allow_lsu_b
  io.lsu_bus.b.bits    := lsu_b_q.io.deq.bits
  lsu_b_q.io.deq.ready := io.lsu_bus.b.ready && allow_lsu_b
}