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

  // --- 配置参数 ---
  // FIFO 深度决定了能容纳多少个"飞行中"的请求
  // 如果总线延迟很大，增加这个深度可以避免阻塞
  val pipelineDepth = 4 

  // --- 内部数据结构定义 ---
  // 用来记录发出的请求信息，以便在响应回来时配对
  class IfuMetaBundle extends Bundle {
    val pc    = UInt(32.W)
    val epoch = Bool() // 用于判断指令是否有效
  }

  // --- 寄存器与FIFO ---
  val pc_reg    = RegInit("h8000_0000".U(32.W))
  val epoch_reg = RegInit(false.B) // 当前系统的 Epoch
  
  // 记录已发出请求的元数据队列 (Meta Queue)
  // 当 req 发出时入队，当 resp 回来时出队
  val meta_queue = Module(new Queue(new IfuMetaBundle, pipelineDepth, pipe = true))

  // --- 1. 请求阶段 (Request Stage) ---
  
  // 处理 Redirect
  // 注意：Redirect 优先级最高，一旦发生，立刻更新 PC 并翻转 Epoch
  val is_redirect = io.redirect.valid
  
  when(is_redirect) {
    pc_reg    := io.redirect.bits.targetPC
    epoch_reg := !epoch_reg // 翻转 Epoch，作废之前发出的所有请求
  } .elsewhen(io.bus.req.fire) {
    pc_reg := pc_reg + 4.U // 只有请求成功发出时，PC才自增
  }

  // 总线请求逻辑
  // 只有当 Meta Queue 有空位能存下元数据时，我们才允许发请求
  val req_valid = meta_queue.io.enq.ready && !reset.asBool
  
  io.bus.req.valid      := req_valid
  io.bus.req.bits.addr  := pc_reg // 注意：这里永远发当前的 pc_reg，如果是跳转，pc_reg 在上一拍/本拍已经更新
  io.bus.req.bits.wen   := false.B
  io.bus.req.bits.wdata := 0.U
  io.bus.req.bits.wmask := 0.U

  // 同时将元数据推入队列
  meta_queue.io.enq.valid      := io.bus.req.fire // 只有 Bus 握手成功才入队
  meta_queue.io.enq.bits.pc    := pc_reg
  meta_queue.io.enq.bits.epoch := epoch_reg 

  // --- 2. 响应阶段 (Response Stage) ---

  // 这里的逻辑是：
  // Bus 回来了数据 (bus.resp) AND Meta Queue 里有对应的记录 (meta_queue.deq)
  // 只有两者都 Ready/Valid，我们才能处理
  
  val resp_valid = io.bus.resp.valid
  val meta_valid = meta_queue.io.deq.valid
  
  // 检查是否是"好"指令 (Epoch 匹配)
  val is_valid_inst = meta_queue.io.deq.bits.epoch === epoch_reg
  
  // 握手逻辑：
  // 什么时候我们要消耗掉 Bus 的 resp 和 Queue 的 meta？
  // 1. 如果是有效指令：下游 (io.out) 接收了 (io.out.ready)
  // 2. 如果是无效指令 (被冲刷掉的)：我们要直接吞掉，不用管下游 ready
  
  val fire_transfer = resp_valid && meta_valid
  val output_fire   = io.out.fire
  val drop_fire     = fire_transfer && !is_valid_inst // 发生冲刷，内部消化
  
  // 控制 Bus 和 Queue 的 Ready
  // 只要下游肯收，或者我们需要丢弃垃圾，就告诉总线/队列 "我处理完了"
  io.bus.resp.ready       := (is_valid_inst && io.out.ready) || (!is_valid_inst)
  meta_queue.io.deq.ready := io.bus.resp.ready && io.bus.resp.valid

  // --- 3. 输出赋值 ---
  
  io.out.valid     := fire_transfer && is_valid_inst
  io.out.bits.inst := io.bus.resp.bits.rdata
  io.out.bits.pc   := meta_queue.io.deq.bits.pc

}
// package corewithbus

// import chisel3._
// import chisel3.util._
// import common._

// class IFU extends Module {
//   val io = IO(new Bundle {                   
//     val bus = new SimpleBus                  
//     val out = Decoupled(new Bundle {         
//       val inst = UInt(32.W)                  
//       val pc   = UInt(32.W)                  
//     })                                       
//     val redirect = Flipped(Valid(new Bundle {
//       val targetPC = UInt(32.W)              
//     }))                                      
//   })                                         

//   // 状态定义
//   val s_idle :: s_wait_normal_resp :: s_wait_jump_resp :: s_flush :: Nil = Enum(4)
//   val state = RegInit(s_idle)

//   // 寄存器
//   val pc = RegInit("h8000_0000".U(32.W))

//   val reg_target_pc = Reg(UInt(32.W))
//   val reg_pending   = RegInit(false.B)

//   // 实时信号
//   val is_redirect = io.redirect.valid
//   val target_pc   = Mux(is_redirect, io.redirect.bits.targetPC, reg_target_pc)

//   // 1. 定义一个寄存器记录上一拍的 redirect.valid 状态
//   val redirect_valid_last = RegNext(io.redirect.valid, false.B)
//   // 2. 检测上升沿 (Current is High, Last was Low)
//   val redirect_edge = io.redirect.valid && !redirect_valid_last
//   when(redirect_edge) {
//       reg_target_pc := io.redirect.bits.targetPC
//       reg_pending   := true.B
//   }

// // --- 状态转移逻辑 ---
//   val receive_jump = redirect_edge || reg_pending

//   switch(state) {
//     is(s_idle) {
//       when(io.bus.req.fire) {
//         state := Mux(receive_jump, s_wait_jump_resp, s_wait_normal_resp)
//         reg_pending := false.B // 请求已成功进入总线，清除 Pending
//       }
//     }
//     is(s_wait_normal_resp) {
//       when(io.bus.resp.valid) {
//         state := s_idle
//       } .elsewhen(redirect_edge) {
//         state := s_flush // 运行中突然跳转，当前包变垃圾，进 flush
//       }
//     }
//     is(s_wait_jump_resp) {
//       when(io.bus.resp.valid) {
//         state := s_idle
//       } .elsewhen(redirect_edge) {
//         // 即使正在等新包，如果又来个新跳转，这个包也变垃圾了
//         state := s_flush 
//       }
//     }
//     is(s_flush) {
//       when(io.bus.resp.valid) {
//         state := s_idle // 垃圾清理完毕，回 idle 准备发最新的 reg_target_pc
//       }
//     }
//   }

//   // --- 组合逻辑 ---

//   // 逻辑：如果需要冲刷，强制 ready；如果是好包，看后级 ready
//   val is_need_flush = (state === s_flush) || 
//                        (state === s_wait_normal_resp && redirect_edge) || 
//                        (state === s_wait_jump_resp && redirect_edge)
                       
//   io.bus.resp.ready := is_need_flush || io.out.ready
//   // 地址选择逻辑
//   // 正在发生的边沿跳转优先级最高，其次是之前存下的 pending 地址
//   val current_target = Mux(redirect_edge, io.redirect.bits.targetPC, reg_target_pc)
//   val use_target = redirect_edge || reg_pending

//   io.bus.req.valid := (state === s_idle) && !reset.asBool
//   io.bus.req.bits.addr  := Mux(use_target, current_target, pc)
//   io.bus.req.bits.wen   := false.B
//   io.bus.req.bits.wdata := 0.U
//   io.bus.req.bits.wmask := 0.U

//   // 修正后的输出赋值
//   io.out.valid := ((state === s_wait_jump_resp) || (state === s_wait_normal_resp)) && io.bus.resp.valid && !redirect_edge
  
//   // 必须对 Bundle 内部的每一个字段进行赋值
//   io.out.bits.inst := io.bus.resp.bits.rdata
//   io.out.bits.pc   := Mux(state === s_wait_jump_resp, reg_target_pc, pc)

//   // PC 更新
//   when(redirect_edge) {
//     pc := io.redirect.bits.targetPC
//   } .elsewhen(io.out.fire) {
//     pc := pc + 4.U
//   }
// }