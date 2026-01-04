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

  // 状态定义
  val s_idle :: s_wait_normal_resp :: s_wait_jump_resp :: s_flush :: Nil = Enum(4)
  val state = RegInit(s_idle)

  // 寄存器
  val pc = RegInit("h8000_0000".U(32.W))

  val reg_target_pc = Reg(UInt(32.W))
  val reg_pending   = RegInit(false.B)

  // 实时信号
  val is_redirect = io.redirect.valid
  val target_pc   = Mux(is_redirect, io.redirect.bits.targetPC, reg_target_pc)

  // 1. 定义一个寄存器记录上一拍的 redirect.valid 状态
  val redirect_valid_last = RegNext(io.redirect.valid, false.B)
  // 2. 检测上升沿 (Current is High, Last was Low)
  val redirect_edge = io.redirect.valid && !redirect_valid_last
  when(redirect_edge) {
      reg_target_pc := io.redirect.bits.targetPC
      reg_pending   := true.B
  }

// --- 状态转移逻辑 ---
  val receive_jump = redirect_edge || reg_pending

  switch(state) {
    is(s_idle) {
      when(io.bus.req.fire) {
        state := Mux(receive_jump, s_wait_jump_resp, s_wait_normal_resp)
        reg_pending := false.B // 请求已成功进入总线，清除 Pending
      }
    }
    is(s_wait_normal_resp) {
      when(io.bus.resp.valid) {
        state := s_idle
      } .elsewhen(redirect_edge) {
        state := s_flush // 运行中突然跳转，当前包变垃圾，进 flush
      }
    }
    is(s_wait_jump_resp) {
      when(io.bus.resp.valid) {
        state := s_idle
      } .elsewhen(redirect_edge) {
        // 即使正在等新包，如果又来个新跳转，这个包也变垃圾了
        state := s_flush 
      }
    }
    is(s_flush) {
      when(io.bus.resp.valid) {
        state := s_idle // 垃圾清理完毕，回 idle 准备发最新的 reg_target_pc
      }
    }
  }

  // --- 组合逻辑 ---

  // 逻辑：如果需要冲刷，强制 ready；如果是好包，看后级 ready
  val is_need_flush = (state === s_flush) || 
                       (state === s_wait_normal_resp && redirect_edge) || 
                       (state === s_wait_jump_resp && redirect_edge)
                       
  io.bus.resp.ready := is_need_flush || io.out.ready
  // 地址选择逻辑
  // 正在发生的边沿跳转优先级最高，其次是之前存下的 pending 地址
  val current_target = Mux(redirect_edge, io.redirect.bits.targetPC, reg_target_pc)
  val use_target = redirect_edge || reg_pending

  io.bus.req.valid := (state === s_idle) && !reset.asBool
  io.bus.req.bits.addr  := Mux(use_target, current_target, pc)
  io.bus.req.bits.wen   := false.B
  io.bus.req.bits.wdata := 0.U
  io.bus.req.bits.wmask := 0.U

  // 修正后的输出赋值
  io.out.valid := ((state === s_wait_jump_resp) || (state === s_wait_normal_resp)) && io.bus.resp.valid && !redirect_edge
  
  // 必须对 Bundle 内部的每一个字段进行赋值
  io.out.bits.inst := io.bus.resp.bits.rdata
  io.out.bits.pc   := Mux(state === s_wait_jump_resp, reg_target_pc, pc)

  // PC 更新
  when(redirect_edge) {
    pc := io.redirect.bits.targetPC
  } .elsewhen(io.out.fire) {
    pc := pc + 4.U
  }
}