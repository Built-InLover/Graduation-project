package corewithbus

import chisel3._
import chisel3.util._
import common._

class IFU extends Module {
  val io = IO(new Bundle {
    val bus = new AXI4Interface(AXI4Params(32, 32, 4))
    val out = Decoupled(new Bundle {
      val inst = UInt(32.W)
      val pc   = UInt(32.W)
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
  
  val pc_reg    = RegInit("h2000_0000".U(32.W))
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
  val req_valid = meta_queue.io.enq.ready && !reset.asBool
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
  //                        3. AXI 读响应连线 (R 通道)
  // ==================================================================
  val resp_valid = io.bus.r.valid // [修改]
  val meta_valid = meta_queue.io.deq.valid
  val is_valid_inst = meta_queue.io.deq.bits.epoch === epoch_reg

  val fire_transfer = resp_valid && meta_valid
  val output_fire   = io.out.fire
  val drop_fire     = fire_transfer && !is_valid_inst 

  // 控制 R 通道和 Queue 的 Ready
  io.bus.r.ready          := (is_valid_inst && io.out.ready) || (!is_valid_inst)
  meta_queue.io.deq.ready := io.bus.r.ready && io.bus.r.valid

  io.out.valid     := fire_transfer && is_valid_inst
  io.out.bits.inst := io.bus.r.bits.data // [修改]
  io.out.bits.pc   := meta_queue.io.deq.bits.pc
}