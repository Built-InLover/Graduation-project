package top

import chisel3._
import chisel3.util._
import core._
import common._

class ysyx_23060000 extends Module {
  val io = IO(new Bundle {
    val interrupt = Input(Bool())
    val master = new AXI4Interface(AXI4Params(32, 32, 4))
    val slave  = Flipped(new AXI4Interface(AXI4Params(32, 32, 4)))
  })

  val core  = Module(new DistributedCore)
  val clint = Module(new AXI4CLINT)

  // ==================================================================
  //  LSU 总线路由：CLINT 地址拦截
  // ==================================================================
  val lsu_ar_addr = core.io.lsu_bus.ar.bits.addr
  val lsu_aw_addr = core.io.lsu_bus.aw.bits.addr
  val is_clint_r  = lsu_ar_addr(31, 16) === "h0200".U
  val is_clint_w  = lsu_aw_addr(31, 16) === "h0200".U

  // 记录当前未完成的读请求是否是 CLINT
  val r_to_clint = RegInit(false.B)
  when(core.io.lsu_bus.ar.fire) {
    r_to_clint := is_clint_r
  }
  // 记录当前未完成的写请求是否是 CLINT
  val w_to_clint = RegInit(false.B)
  when(core.io.lsu_bus.aw.fire) {
    w_to_clint := is_clint_w
  }

  // LSU -> CLINT (读)
  clint.io.bus.ar.valid := core.io.lsu_bus.ar.valid && is_clint_r
  clint.io.bus.ar.bits  := core.io.lsu_bus.ar.bits

  // LSU -> CLINT (写)
  clint.io.bus.aw.valid := core.io.lsu_bus.aw.valid && is_clint_w
  clint.io.bus.aw.bits  := core.io.lsu_bus.aw.bits
  clint.io.bus.w.valid  := core.io.lsu_bus.w.valid && is_clint_w
  clint.io.bus.w.bits   := core.io.lsu_bus.w.bits

  // CLINT 响应
  clint.io.bus.r.ready := core.io.lsu_bus.r.ready && r_to_clint
  clint.io.bus.b.ready := core.io.lsu_bus.b.ready && w_to_clint

  // ==================================================================
  //  LSU 外部总线（非 CLINT 部分）
  // ==================================================================
  // 用临时 wire 保存 LSU 到外部的 AXI4 信号
  val lsu_ext_ar_valid = core.io.lsu_bus.ar.valid && !is_clint_r
  val lsu_ext_aw_valid = core.io.lsu_bus.aw.valid && !is_clint_w
  val lsu_ext_w_valid  = core.io.lsu_bus.w.valid && !is_clint_w

  // LSU AR ready: 来自 CLINT 或外部
  core.io.lsu_bus.ar.ready := Mux(is_clint_r, clint.io.bus.ar.ready, false.B) // 外部 ready 在仲裁中处理

  // LSU AW/W ready
  core.io.lsu_bus.aw.ready := Mux(is_clint_w, clint.io.bus.aw.ready, false.B)
  core.io.lsu_bus.w.ready  := Mux(is_clint_w, clint.io.bus.w.ready, false.B)

  // LSU R 响应 mux
  core.io.lsu_bus.r.valid := Mux(r_to_clint, clint.io.bus.r.valid, false.B)
  core.io.lsu_bus.r.bits  := Mux(r_to_clint, clint.io.bus.r.bits, 0.U.asTypeOf(chiselTypeOf(core.io.lsu_bus.r.bits)))

  // LSU B 响应 mux
  core.io.lsu_bus.b.valid := Mux(w_to_clint, clint.io.bus.b.valid, false.B)
  core.io.lsu_bus.b.bits  := Mux(w_to_clint, clint.io.bus.b.bits, 0.U.asTypeOf(chiselTypeOf(core.io.lsu_bus.b.bits)))

  // ==================================================================
  //  IFU + LSU(外部) 仲裁 → Master 端口
  // ==================================================================
  val lsu_ext_req = lsu_ext_ar_valid || lsu_ext_aw_valid

  // AR 通道：LSU 优先
  io.master.ar.valid := Mux(lsu_ext_req, lsu_ext_ar_valid, core.io.ifu_bus.ar.valid)
  io.master.ar.bits  := Mux(lsu_ext_req, core.io.lsu_bus.ar.bits, core.io.ifu_bus.ar.bits)

  // IFU AR ready
  core.io.ifu_bus.ar.ready := io.master.ar.ready && !lsu_ext_req

  // LSU AR ready（外部部分，覆盖上面的默认值）
  when(!is_clint_r) {
    core.io.lsu_bus.ar.ready := io.master.ar.ready && lsu_ext_req
  }

  // AW 通道（只有 LSU 外部使用）
  io.master.aw.valid := lsu_ext_aw_valid
  io.master.aw.bits  := core.io.lsu_bus.aw.bits
  core.io.ifu_bus.aw.ready := false.B
  when(!is_clint_w) {
    core.io.lsu_bus.aw.ready := io.master.aw.ready
  }

  // W 通道（只有 LSU 外部使用）
  io.master.w.valid := lsu_ext_w_valid
  io.master.w.bits  := core.io.lsu_bus.w.bits
  core.io.ifu_bus.w.ready := false.B
  when(!is_clint_w) {
    core.io.lsu_bus.w.ready := io.master.w.ready
  }

  // B 通道（只有 LSU 外部使用）
  io.master.b.ready := core.io.lsu_bus.b.ready && !w_to_clint
  core.io.ifu_bus.b.valid := false.B
  core.io.ifu_bus.b.bits  := DontCare
  when(!w_to_clint) {
    core.io.lsu_bus.b.valid := io.master.b.valid
    core.io.lsu_bus.b.bits  := io.master.b.bits
  }

  // R 通道仲裁：根据 R 响应的 id 路由（IFU id=0, LSU id=1）
  val r_is_lsu = io.master.r.bits.id === 1.U

  core.io.ifu_bus.r.valid := io.master.r.valid && !r_is_lsu
  core.io.ifu_bus.r.bits  := io.master.r.bits
  when(!r_to_clint) {
    core.io.lsu_bus.r.valid := io.master.r.valid && r_is_lsu
    core.io.lsu_bus.r.bits  := io.master.r.bits
  }
  io.master.r.ready := Mux(r_is_lsu,
    core.io.lsu_bus.r.ready && !r_to_clint,
    core.io.ifu_bus.r.ready)

  // ==================================================================
  //  Slave 接口（不使用，输出赋 0）
  // ==================================================================
  io.slave.aw.ready := false.B
  io.slave.w.ready  := false.B
  io.slave.b.valid  := false.B
  io.slave.b.bits.id   := 0.U
  io.slave.b.bits.resp := 0.U
  io.slave.ar.ready := false.B
  io.slave.r.valid  := false.B
  io.slave.r.bits.id   := 0.U
  io.slave.r.bits.data := 0.U
  io.slave.r.bits.resp := 0.U
  io.slave.r.bits.last := false.B
}
