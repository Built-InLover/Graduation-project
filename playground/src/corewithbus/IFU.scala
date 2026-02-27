package corewithbus

import chisel3._
import chisel3.util._
import common._

class IFU extends Module {
  val io = IO(new Bundle {
    val bus = new AXI4Interface(AXI4Params(32, 32, 4))
    val out = Decoupled(new Bundle {
      val inst  = UInt(32.W)
      val pc    = UInt(32.W)
      val exception = Bool()
    })
    val redirect = Flipped(Valid(new Bundle {
      val targetPC = UInt(32.W)
    }))
  })
  
  val pipelineDepth = 4
  class IfuMetaBundle extends Bundle {
    val pc    = UInt(32.W)
    val epoch = Bool()
  }
  
  val pc_reg    = RegInit("h3000_0000".U(32.W))
  val epoch_reg = RegInit(false.B)
  val meta_queue = Module(new Queue(new IfuMetaBundle, pipelineDepth, pipe = true))

  val redirect_last = RegNext(io.redirect.valid, false.B)
  val is_redirect_pulse = io.redirect.valid && !redirect_last

  when(is_redirect_pulse) {
    pc_reg    := io.redirect.bits.targetPC
    epoch_reg := !epoch_reg 
  } .elsewhen(io.bus.ar.fire) { // [修改] 原 req.fire 变成 ar.fire
    pc_reg := pc_reg + 4.U
  }

  // ==================================================================
  //                        1. AXI 读请求连线 (AR 通道)
  // ==================================================================
  // AR 请求状态机：确保 arvalid 只持续一拍，防止 SoC buffer 重复接受
  val s_idle :: s_ar :: s_wait :: Nil = Enum(3)
  val ar_state = RegInit(s_idle)
  switch(ar_state) {
    is(s_idle) {
      // 条件满足时进入 s_ar，拉高 arvalid 一拍
      when(meta_queue.io.enq.ready && !reset.asBool) {
        ar_state := s_ar
      }
    }
    is(s_ar) {
      when(io.bus.ar.fire) {
        ar_state := s_wait  // 握手成功，等 R 响应
      }
      // 如果没 fire（ready=0），保持 s_ar 继续等
    }
    is(s_wait) {
      when(io.bus.r.fire) {
        ar_state := s_idle  // R 响应回来，可以发下一个
      }
    }
  }

  val req_valid = (ar_state === s_ar) && !reset.asBool
  io.bus.ar.valid      := req_valid
  io.bus.ar.bits.addr  := pc_reg
  io.bus.ar.bits.id    := 0.U
  io.bus.ar.bits.len   := 0.U
  io.bus.ar.bits.size  := 2.U  // 4 bytes
  io.bus.ar.bits.burst := 1.U  // INCR
  
  meta_queue.io.enq.valid      := io.bus.ar.fire 
  meta_queue.io.enq.bits.pc    := pc_reg
  meta_queue.io.enq.bits.epoch := epoch_reg

  // ==================================================================
  //                        2. AXI 写通道禁用 (AW, W, B)
  // ==================================================================
  io.bus.aw.valid     := false.B
  io.bus.aw.bits      := DontCare
  io.bus.w.valid      := false.B
  io.bus.w.bits       := DontCare
  io.bus.b.ready      := true.B

  // ==================================================================
  //                        3. AXI 读响应 → inst_queue 缓冲
  // ==================================================================
  val inst_queue = Module(new Queue(new Bundle {
    val data  = UInt(32.W)
    val exception = Bool()
  }, pipelineDepth, pipe = true))

  // R 通道 ready 只看 inst_queue 是否能入队，不依赖下游流水线
  io.bus.r.ready := inst_queue.io.enq.ready
  inst_queue.io.enq.valid      := io.bus.r.valid
  inst_queue.io.enq.bits.data  := io.bus.r.bits.data
  inst_queue.io.enq.bits.exception := io.bus.r.bits.resp =/= 0.U

  // ==================================================================
  //                        4. inst_queue + meta_queue 同步出队
  // ==================================================================
  val both_valid = inst_queue.io.deq.valid && meta_queue.io.deq.valid
  val is_valid_inst = meta_queue.io.deq.bits.epoch === epoch_reg

  // 有效指令：等下游接收；无效 epoch：直接丢弃
  val deq_ready = Mux(is_valid_inst, io.out.fire, both_valid)
  inst_queue.io.deq.ready := deq_ready
  meta_queue.io.deq.ready := deq_ready

  io.out.valid      := both_valid && is_valid_inst
  io.out.bits.inst  := inst_queue.io.deq.bits.data
  io.out.bits.pc    := meta_queue.io.deq.bits.pc
  io.out.bits.exception := inst_queue.io.deq.bits.exception
}