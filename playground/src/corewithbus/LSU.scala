package corewithbus

import chisel3._
import chisel3.util._
import common._
import essentials._

class LSU extends Module {
  val io = IO(new Bundle {
    // 1. 内部总线接口 (来自 EXU)
    val in = Flipped(Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val addr   = UInt(32.W)
      val wdata  = UInt(32.W)
      val func   = FuOpType() // 使用 7 位编码
      val rdAddr = UInt(5.W)  // 接收 rdAddr
    }))

    // 2. 输出接口 (去往 WBU)
    val out = Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val rdata  = UInt(32.W)
      val rdAddr = UInt(5.W) // 透传 rdAddr 给 WBU
      val rfWen  = Bool()    // 告诉 WBU 是否需要写寄存器 (Load需要, Store不需要)
    })

    // 3. 外部总线接口 (SimpleBus)
    val bus = new SimpleBus
  })

  io.out.bits.pc   := io.in.bits.pc
  io.out.bits.dnpc := io.in.bits.dnpc

  // --- [1] 状态定义与寄存器 ---
  val s_idle :: s_wait_resp :: Nil = Enum(2)
  val state = RegInit(s_idle)

  // 必须锁存输入信息，因为 io.in 会在握手后消失
  val addr_reg   = RegEnable(io.in.bits.addr,   io.in.fire)
  val wdata_reg  = RegEnable(io.in.bits.wdata,  io.in.fire)
  val func_reg   = RegEnable(io.in.bits.func,   io.in.fire)
  val rdAddr_reg = RegEnable(io.in.bits.rdAddr, io.in.fire) // 锁存 rdAddr

  // --- [2] 组合逻辑处理 (利用输入或寄存值) ---
  val is_idle       = state === s_idle
  val current_addr  = Mux(is_idle, io.in.bits.addr, addr_reg)
  val current_func  = Mux(is_idle, io.in.bits.func, func_reg)
  val current_wdata = Mux(is_idle, io.in.bits.wdata, wdata_reg)

  val offset    = current_addr(1, 0)
  val base_mask = LSUOpType.mask(current_func)
  
  // 写数据对齐：根据地址低两位进行移位
  val shifted_wdata = current_wdata << (offset << 3)
  val shifted_wmask = (base_mask << offset)(3, 0)

  // --- [3] 外部总线请求 (Request) ---
  io.bus.req.valid      := is_idle && io.in.valid
  io.bus.req.bits.addr  := current_addr
  io.bus.req.bits.wen   := LSUOpType.isStore(current_func)
  io.bus.req.bits.wdata := shifted_wdata
  io.bus.req.bits.wmask := shifted_wmask

  // 状态跳转逻辑
  when(io.bus.req.fire) {
    // 如果是 Store，发出请求就算结束（假设总线能立刻收下）；Load 必须等 Resp
    state := Mux(LSUOpType.isStore(current_func), s_idle, s_wait_resp)
  }

  // --- [4] 读数据处理逻辑 ---
  io.bus.resp.ready := (state === s_wait_resp)

  val raw_rdata      = io.bus.resp.bits.rdata
  val offset_reg     = addr_reg(1, 0)
  val shifted_rdata  = raw_rdata >> (offset_reg << 3)
  val is_signed      = LSUOpType.isSigned(func_reg)
  
  val load_result = WireDefault(0.U(32.W))
  switch(func_reg(1, 0)) {
    is("b00".U) { // Byte (LB/LBU)
      val b = shifted_rdata(7, 0)
      load_result := Mux(is_signed, b.asSInt.pad(32).asUInt, b.asUInt)
    }
    is("b01".U) { // Halfword (LH/LHU)
      val h = shifted_rdata(15, 0)
      load_result := Mux(is_signed, h.asSInt.pad(32).asUInt, h.asUInt)
    }
    is("b10".U) { // Word (LW)
      load_result := shifted_rdata
    }
  }

  // --- [5] 内部总线输出 (去 WBU) ---
  // 判断当前正在处理的是 Load 还是 Store (针对锁存后的状态)
  val is_store_op = LSUOpType.isStore(Mux(is_idle, io.in.bits.func, func_reg))
  val is_load_op  = !is_store_op

  // Valid 信号：Store 在 Req.fire 时有效；Load 在 Resp.fire 时有效
  io.out.valid := Mux(state === s_wait_resp, 
                      io.bus.resp.valid, 
                      io.bus.req.fire && is_store_op)
  
  io.out.bits.rdata  := load_result
  io.out.bits.rdAddr := Mux(is_idle, io.in.bits.rdAddr, rdAddr_reg)
  io.out.bits.rfWen  := is_load_op // 只有 Load 指令需要 WBU 写寄存器

  // 状态回转逻辑
  when(io.out.fire && state === s_wait_resp) {
    state := s_idle
  }

  io.in.ready := (state === s_idle) && io.out.ready
}