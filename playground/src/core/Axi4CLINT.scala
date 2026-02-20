package core

import chisel3._
import chisel3.util._
import common._

class AXI4CLINT extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new AXI4Interface(AXI4Params(32, 32, 4)))
  })

  // 内部 64 位计时器，每周期 +1
  val mtime = RegInit(0.U(64.W))
  mtime := mtime + 1.U

  // 1. 读通道
  val r_valid = RegInit(false.B)
  val r_data  = RegInit(0.U(32.W))

  io.bus.ar.ready := !r_valid
  val ar_fire = io.bus.ar.fire

  when(ar_fire) {
    r_valid := true.B
    // mtime 低32位 = 0x0200_BFF8，高32位 = 0x0200_BFFC
    val is_high = io.bus.ar.bits.addr(2)
    r_data := Mux(is_high, mtime(63, 32), mtime(31, 0))
  }.elsewhen(io.bus.r.fire) {
    r_valid := false.B
  }

  io.bus.r.valid     := r_valid
  io.bus.r.bits.data := r_data
  io.bus.r.bits.resp := "b00".U
  io.bus.r.bits.id   := 0.U
  io.bus.r.bits.last := true.B

  // 2. 写通道 (CLINT mtime 暂时设为只读)
  io.bus.aw.ready := false.B
  io.bus.w.ready  := false.B
  io.bus.b.valid  := false.B
  io.bus.b.bits   := DontCare
}
