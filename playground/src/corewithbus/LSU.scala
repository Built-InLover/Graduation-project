package corewithbus

import chisel3._
import chisel3.util._
import common._
import essentials._

class LSU extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val addr   = UInt(32.W)
      val wdata  = UInt(32.W)
      val func   = FuOpType()
      val rdAddr = UInt(5.W)  
      val uop_id = UInt(4.W) 
    }))
    val out = Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val rdata  = UInt(32.W)
      val rdAddr = UInt(5.W)
      val rfWen  = Bool()    
      val uop_id = UInt(4.W) 
    })
    val busy_is_load = Output(Bool())
    val busy_rd      = Output(UInt(5.W))
    val busy_uop_id  = Output(UInt(4.W))
    val bus = new AXI4Interface(AXI4Params(32, 32, 4))
    val pc_mtrace = Output(UInt(32.W))
  })

  val s_idle :: s_wait_resp :: Nil = Enum(2)
  val state = RegInit(s_idle)

  val id_reg     = RegEnable(io.in.bits.uop_id, io.in.fire)
  val pc_reg     = RegEnable(io.in.bits.pc,     io.in.fire)
  val dnpc_reg   = RegEnable(io.in.bits.dnpc,   io.in.fire)
  val addr_reg   = RegEnable(io.in.bits.addr,   io.in.fire)
  val wdata_reg  = RegEnable(io.in.bits.wdata,  io.in.fire)
  val func_reg   = RegEnable(io.in.bits.func,   io.in.fire)
  val rdAddr_reg = RegEnable(io.in.bits.rdAddr, io.in.fire)

  val is_idle = state === s_idle
  val current_func = Mux(is_idle, io.in.bits.func, func_reg)
  val cmd_is_store = LSUOpType.isStore(current_func)
  val cmd_is_load  = !cmd_is_store

  io.pc_mtrace := pc_reg

  val req_addr  = Mux(is_idle, io.in.bits.addr,  addr_reg)
  val req_wdata = Mux(is_idle, io.in.bits.wdata, wdata_reg)
  val offset    = req_addr(1, 0)
  val base_mask = LSUOpType.mask(current_func)
  val shifted_wdata = req_wdata << (offset << 3)
  val shifted_wmask = (base_mask << offset)(3, 0)

  // ==================================================================
  //                        1. AXI 发起请求 (Request Path)
  // ==================================================================
  val can_req = is_idle && io.in.valid && !reset.asBool
  
  // AR 通道 (Load)
  io.bus.ar.valid      := can_req && cmd_is_load
  io.bus.ar.bits.addr  := req_addr
  io.bus.ar.bits.id    := 0.U
  io.bus.ar.bits.len   := 0.U
  io.bus.ar.bits.size  := current_func(1, 0)  // lb=0, lh=1, lw=2
  io.bus.ar.bits.burst := 1.U  // INCR

  // AW / W 通道 (Store - 采用同时发送策略)
  val is_store_req = can_req && cmd_is_store
  io.bus.aw.valid      := is_store_req
  io.bus.aw.bits.addr  := req_addr
  io.bus.aw.bits.id    := 0.U
  io.bus.aw.bits.len   := 0.U
  io.bus.aw.bits.size  := current_func(1, 0)  // sb=0, sh=1, sw=2
  io.bus.aw.bits.burst := 1.U  // INCR

  io.bus.w.valid      := is_store_req
  io.bus.w.bits.data  := shifted_wdata
  io.bus.w.bits.strb  := shifted_wmask
  io.bus.w.bits.last  := true.B  // 单拍传输

  // 握手条件
  val ar_ready = io.bus.ar.ready
  val aw_w_ready = io.bus.aw.ready && io.bus.w.ready // Store 必须地址和数据都 Ready 才能进 Wait  AW和W捆绑，简化设计
  
  val bus_ready_for_req = Mux(cmd_is_store, aw_w_ready, ar_ready)
  val handshake_condition = Mux(cmd_is_store, bus_ready_for_req && io.out.ready, bus_ready_for_req)
  
  io.in.ready := is_idle && handshake_condition

  val ar_fire = io.bus.ar.fire
  val aw_w_fire = is_store_req && aw_w_ready

  when(ar_fire || aw_w_fire) {
    state := s_wait_resp
  }

  // ==================================================================
  //                        2. AXI 接收响应 (Response Path)
  // ==================================================================
  val wait_is_load = LSUOpType.isLoad(func_reg)
  val wait_is_store = LSUOpType.isStore(func_reg)

  val raw_rdata     = io.bus.r.bits.data // [修改]
  val read_offset   = addr_reg(1, 0)
  val shifted_rdata = raw_rdata >> (read_offset << 3)
  val is_signed     = LSUOpType.isSigned(func_reg)
  val load_result   = WireDefault(0.U(32.W))
  
  switch(func_reg(1, 0)) {
    is("b00".U) { load_result := Mux(is_signed, shifted_rdata(7, 0).asSInt.pad(32).asUInt, shifted_rdata(7, 0)) }
    is("b01".U) { load_result := Mux(is_signed, shifted_rdata(15, 0).asSInt.pad(32).asUInt, shifted_rdata(15, 0)) }
    is("b10".U) { load_result := shifted_rdata }
  }

  // 读写独立响应 Ready 控制
  io.bus.r.ready := (state === s_wait_resp) && wait_is_load && io.out.ready
  io.bus.b.ready := (state === s_wait_resp) && wait_is_store && io.out.ready

  val resp_valid = Mux(wait_is_load, io.bus.r.valid, io.bus.b.valid)

  when(state === s_wait_resp && io.out.fire) {
    state := s_idle
  }

  io.out.valid := (state === s_wait_resp) && resp_valid
  io.out.bits.pc     := pc_reg
  io.out.bits.dnpc   := dnpc_reg
  io.out.bits.rdata  := load_result
  io.out.bits.rdAddr := rdAddr_reg
  io.out.bits.uop_id := id_reg
  io.out.bits.rfWen  := (state === s_wait_resp) && wait_is_load

  io.busy_is_load := (state === s_wait_resp) && wait_is_load
  io.busy_rd      := rdAddr_reg
  io.busy_uop_id  := id_reg
}