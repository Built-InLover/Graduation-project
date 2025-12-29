package corewithbus

import chisel3._     
import chisel3.util._
import common._

class IFU extends Module {
  val io = IO(new Bundle {
    val bus = new SimpleBus
    
    val out = Decoupled(new Bundle {
      val inst = UInt(32.W)
      val pc   = UInt(32.W)
    })
    val redirect = Flipped(Valid(new Bundle {
      val targetPC = UInt(32.W)
    }))
  })

  val pc = RegInit("h8000_0000".U(32.W))
  
  val s_idle :: s_wait_resp :: Nil = Enum(2)
  val state = RegInit(s_idle)

  // 新增标志位：标记当前总线上是否有一个“已经发出但需要作废”的请求
  val inflight_trash = RegInit(false.B)

  // --- [2] 跳转逻辑 (优先级最高) ---
  when(io.redirect.valid) {
    pc := io.redirect.bits.targetPC
    state := s_idle
    // 如果跳转发生时，我们正在等待总线返回，说明那个返回的数据是错的
    // 我们记录下这个“垃圾数据”状态
    when(state === s_wait_resp) {
      inflight_trash := true.B
    }
  }

  // --- [3] 总线请求阶段 ---
  // 发起请求的条件：处于 idle，且没有待清理的垃圾数据，且当前没有跳转
  io.bus.req.valid     := (state === s_idle) && !inflight_trash && !io.redirect.valid && !reset.asBool
  io.bus.req.bits.addr := pc
  io.bus.req.bits.wen  := false.B
  io.bus.req.bits.wdata := 0.U
  io.bus.req.bits.wmask := 0.U

  when(io.bus.req.fire) {
    state := s_wait_resp
  }

  // --- [4] 总线响应与输出 ---
  
  // 核心逻辑：拦截垃圾数据
  // 如果当前返回的是垃圾数据，我们直接 ready 把它吞掉，不传给 IDU
  val is_resp_trash = (state === s_wait_resp) && inflight_trash
  
  io.bus.resp.ready := io.out.ready || is_resp_trash

  // 发给 IDU 的 valid 必须保证不是垃圾数据且没在跳转中
  io.out.valid      := (state === s_wait_resp) && io.bus.resp.valid && !inflight_trash && !io.redirect.valid
  io.out.bits.inst  := io.bus.resp.bits.rdata
  io.out.bits.pc    := pc

  // 状态跳转逻辑
  when(state === s_wait_resp && io.bus.resp.valid) {
    when(inflight_trash) {
      // 垃圾数据回来了，吞掉它，清空标志位，回到 idle 重新取跳转后的新地址
      inflight_trash := false.B
      state := s_idle
    }.elsewhen(io.out.ready) {
      // 正常数据握手成功
      pc    := pc + 4.U
      state := s_idle
    }
  }
}
