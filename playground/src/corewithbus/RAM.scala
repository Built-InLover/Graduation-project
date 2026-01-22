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
    val ifu_bus = Flipped(new SimpleBus())
    val lsu_bus = Flipped(new SimpleBus())
  })

  val bb = Module(new MemBlackBox())
  bb.io.clock := clock

  // ==================================================================
  //                        1. IFU 通道 (修复版)
  // ==================================================================
  
  // 建立一个队列来缓冲内存返回的数据
  // entries=1, pipe=true 保证在不阻塞时，延迟依然是 1 周期，没有性能损失
  val ifu_resp_q = Module(new Queue(chiselTypeOf(io.ifu_bus.resp.bits), entries = 4, pipe = true))

  // --- 请求侧 ---
  bb.io.ifu_addr := io.ifu_bus.req.bits.addr
  
  // [关键修正 1] 只有当响应队列有空位时，才允许发新请求
  // 否则，如果 CPU 一直不收数据，队列满了，新请求的数据会覆盖旧数据
  val ifu_req_valid = io.ifu_bus.req.valid
  io.ifu_bus.req.ready := ifu_resp_q.io.enq.ready 
  
  // 只有握手成功，才触发 BlackBox 读操作
  val ifu_fire = io.ifu_bus.req.fire
  bb.io.ifu_en := ifu_fire

  // --- 响应侧 ---
  // 将 BlackBox 吐出的数据推入队列
  // RegNext 捕获 fire 后的下一拍（即数据有效的那一拍）
  ifu_resp_q.io.enq.valid := RegNext(ifu_fire, false.B)
  ifu_resp_q.io.enq.bits.rdata := bb.io.ifu_data
  
  // 将队列输出连接到总线响应
  io.ifu_bus.resp <> ifu_resp_q.io.deq


  // ==================================================================
  //                        2. LSU 通道 (修复版)
  // ==================================================================
  // 同样的逻辑应用于 LSU，防止 LSU 忙于处理上一条 Store 时丢失下一条 Load 的数据
  
  val lsu_resp_q = Module(new Queue(chiselTypeOf(io.lsu_bus.resp.bits), entries = 4, pipe = true))

  bb.io.lsu_addr  := io.lsu_bus.req.bits.addr
  bb.io.lsu_wdata := io.lsu_bus.req.bits.wdata
  bb.io.lsu_wmask := io.lsu_bus.req.bits.wmask
  bb.io.lsu_wen   := io.lsu_bus.req.bits.wen
  
  // [关键修正 2] 压住请求，直到响应队列有空位
  io.lsu_bus.req.ready := lsu_resp_q.io.enq.ready
  
  val lsu_fire = io.lsu_bus.req.fire
  bb.io.lsu_en := lsu_fire

  // 缓冲响应
  lsu_resp_q.io.enq.valid := RegNext(lsu_fire, false.B)
  lsu_resp_q.io.enq.bits.rdata := bb.io.lsu_rdata
  
  io.lsu_bus.resp <> lsu_resp_q.io.deq
}