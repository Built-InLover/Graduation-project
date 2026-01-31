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
    // 1.记录上一拍的 redirect 状态
  val redirect_last = RegNext(io.redirect.valid, false.B)
    // 2. 检测上升沿：现在是高，上一拍是低
  // 只有在上升沿这一拍，才是“新的跳转请求”
  val is_redirect_pulse = io.redirect.valid && !redirect_last
    // 3. 使用脉冲信号控制逻辑
  when(is_redirect_pulse) {
    pc_reg    := io.redirect.bits.targetPC
    epoch_reg := !epoch_reg 
  } .elsewhen(io.bus.req.fire) {
    pc_reg := pc_reg + 4.U
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