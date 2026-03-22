package corewithbus

import chisel3._
import chisel3.util._
import common._

class IFU(icacheCfg: ICacheConfig = ICacheConfig()) extends Module {
  val io = IO(new Bundle {
    val bus = new AXI4Interface(AXI4Params(icacheCfg.addrBits, icacheCfg.dataBits, 4))
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
  val icache = Module(new ICache(icacheCfg))

  class IfuMetaBundle extends Bundle {
    val pc    = UInt(32.W)
    val epoch = Bool()
  }

  val pc_reg     = RegInit("h3000_0000".U(32.W))
  val epoch_reg  = RegInit(false.B)
  val meta_queue = Module(new Queue(new IfuMetaBundle, pipelineDepth, pipe = true))

  val redirect_last = RegNext(io.redirect.valid, false.B)
  val is_redirect_pulse = io.redirect.valid && !redirect_last

  when(is_redirect_pulse) {
    pc_reg := io.redirect.bits.targetPC
    epoch_reg := !epoch_reg
  }.elsewhen(icache.io.cpu.req.fire) {
    pc_reg := pc_reg + 4.U
  }

  io.bus <> icache.io.bus

  val req_valid = meta_queue.io.enq.ready && !reset.asBool && !is_redirect_pulse
  icache.io.cpu.req.valid := req_valid
  icache.io.cpu.req.bits.addr := pc_reg

  meta_queue.io.enq.valid := icache.io.cpu.req.fire
  meta_queue.io.enq.bits.pc := pc_reg
  meta_queue.io.enq.bits.epoch := epoch_reg

  val inst_queue = Module(new Queue(new Bundle {
    val data  = UInt(32.W)
    val exception = Bool()
  }, pipelineDepth, pipe = true))

  icache.io.cpu.resp.ready := inst_queue.io.enq.ready
  inst_queue.io.enq.valid := icache.io.cpu.resp.valid
  inst_queue.io.enq.bits.data := icache.io.cpu.resp.bits.data
  inst_queue.io.enq.bits.exception := icache.io.cpu.resp.bits.exception

  val both_valid = inst_queue.io.deq.valid && meta_queue.io.deq.valid
  val is_valid_inst = meta_queue.io.deq.bits.epoch === epoch_reg

  val deq_ready = Mux(is_valid_inst, io.out.fire, both_valid)
  inst_queue.io.deq.ready := deq_ready
  meta_queue.io.deq.ready := deq_ready

  io.out.valid := both_valid && is_valid_inst
  io.out.bits.inst := inst_queue.io.deq.bits.data
  io.out.bits.pc := meta_queue.io.deq.bits.pc
  io.out.bits.exception := inst_queue.io.deq.bits.exception
}
