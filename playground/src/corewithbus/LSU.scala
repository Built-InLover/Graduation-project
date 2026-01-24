package corewithbus

import chisel3._
import chisel3.util._
import common._
import essentials._

class LSU extends Module {
  val io = IO(new Bundle {
    // 1. 来自 EXU 的请求
    val in = Flipped(Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val addr   = UInt(32.W)
      val wdata  = UInt(32.W)
      val func   = FuOpType()
      val rdAddr = UInt(5.W)  
    }))
    // 2. 发往 WBU 的结果
    val out = Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val rdata  = UInt(32.W)
      val rdAddr = UInt(5.W)
      val rfWen  = Bool()    
    })
    // 3. 冒险检测信号 (给 IDU)
    val is_load = Output(Bool())
    val load_rd = Output(UInt(5.W))
    // 4. 外部总线接口
    val bus = new SimpleBus
    // 5.pc for mtrace
    val pc_mtrace = Output(UInt(32.W))
  })
  // ==================================================================
  //                        1. 状态机与寄存器
  // ==================================================================
  val s_idle :: s_wait_resp :: Nil = Enum(2)
  val state = RegInit(s_idle)
  // [关键修复] PC 和 DNPC 必须锁存，不能依赖 io.in 在等待期间保持不变
  val pc_reg     = RegEnable(io.in.bits.pc,     io.in.fire)
  val dnpc_reg   = RegEnable(io.in.bits.dnpc,   io.in.fire)
  val addr_reg   = RegEnable(io.in.bits.addr,   io.in.fire)
  val wdata_reg  = RegEnable(io.in.bits.wdata,  io.in.fire)
  val func_reg   = RegEnable(io.in.bits.func,   io.in.fire)
  val rdAddr_reg = RegEnable(io.in.bits.rdAddr, io.in.fire)
  // [修复命名冲突] 使用 cmd_is_store/load 避免与 io.is_loading 混淆
  val is_idle = state === s_idle
  // 如果是 Idle，看输入；如果是 Wait，看寄存器
  val current_func = Mux(is_idle, io.in.bits.func, func_reg)
  val cmd_is_store = LSUOpType.isStore(current_func)
  val cmd_is_load  = !cmd_is_store
  io.pc_mtrace := pc_reg
  // ==================================================================
  //                        2. 发起请求 (Request Path)
  // ==================================================================
  val req_addr  = Mux(is_idle, io.in.bits.addr,  addr_reg)
  val req_wdata = Mux(is_idle, io.in.bits.wdata, wdata_reg)
  val offset    = req_addr(1, 0)
  val base_mask = LSUOpType.mask(current_func)
  val shifted_wdata = req_wdata << (offset << 3)
  val shifted_wmask = (base_mask << offset)(3, 0)
  // --- 总线请求连线 ---
  io.bus.req.valid      := is_idle && io.in.valid && !reset.asBool
  io.bus.req.bits.addr  := req_addr
  io.bus.req.bits.wen   := cmd_is_store
  io.bus.req.bits.wdata := shifted_wdata
  io.bus.req.bits.wmask := shifted_wmask
  // --- 输入握手逻辑 (Back Pressure) ---
  // 这里解答你的疑问：
  // 只要 state 不是 s_idle，io.in.ready 就会被拉低。
  // 这就保证了在 Load 等待期间，不会接受新的指令，不会覆盖寄存器。
  val out_ready_for_store = io.out.ready // Store 需要 WBU 准备好接收"写完成"信号
  val bus_ready_for_req   = io.bus.req.ready
  // 对于 Store: Bus要能发 && 下游要能收结果
  // 对于 Load:  Bus要能发 (下游收结果是以后数据回来之后的事)
  val handshake_condition = Mux(cmd_is_store,
                                bus_ready_for_req && out_ready_for_store,
                                bus_ready_for_req)
  io.in.ready := is_idle && handshake_condition
  // --- 状态跳转 ---
 // [修改点 1] 状态机跳转：Store 和 Load 都进入等待状态
  when(io.bus.req.fire) {
    state := s_wait_resp
  }
  // ==================================================================
  //                        3. 接收响应 (Response Path)
  // ==================================================================
  val raw_rdata     = io.bus.resp.bits.rdata
  val read_offset   = addr_reg(1, 0)
  val shifted_rdata = raw_rdata >> (read_offset << 3)
  val is_signed     = LSUOpType.isSigned(func_reg)
  val load_result = WireDefault(0.U(32.W))
  switch(func_reg(1, 0)) {
    is("b00".U) { // Byte
      val b = shifted_rdata(7, 0)
      load_result := Mux(is_signed, b.asSInt.pad(32).asUInt, b.asUInt)
    }
    is("b01".U) { // Half
      val h = shifted_rdata(15, 0)
      load_result := Mux(is_signed, h.asSInt.pad(32).asUInt, h.asUInt)
    }
    is("b10".U) { // Word
      load_result := shifted_rdata
    }
  }
  // [修改点 2] 握手逻辑：现在 Store 也要等 resp，所以逻辑通用了
  // 只有当 WBU 准备好接收结果时，我们才吃掉 Bus 的 resp
  io.bus.resp.ready := (state === s_wait_resp) && io.out.ready
  // 状态回转
  when(state === s_wait_resp && io.out.fire) {
    state := s_idle
  }
  // ==================================================================
  //                        4. 输出到 WBU
  // ==================================================================
  // [修改点 3] 统一输出 Valid 逻辑
  // 只有在等待状态，且总线回复了 Valid，才算完成
  io.out.valid := (state === s_wait_resp) && io.bus.resp.valid
  // PC, DNPC 保持原样 (使用寄存器锁存的值)
  io.out.bits.pc     := pc_reg
  io.out.bits.dnpc   := dnpc_reg
  io.out.bits.rdata  := load_result
  io.out.bits.rdAddr := rdAddr_reg
  // rfWen 只有在是 Load 指令时才拉高 (Store 虽然等了 resp，但不写寄存器)
  // 注意：这里要用寄存器里的 cmd_is_load 判断，因为现在是在 wait 状态
  // 之前定义的 cmd_is_load 是基于 io.in 的，这里需要一个新的定义或者复用逻辑
  // 建议复用 func_reg 判断
  io.out.bits.rfWen  := (state === s_wait_resp) && LSUOpType.isLoad(func_reg)
// ==================================================================
  //                        5. 冒险检测信号 (修复版 - 穿透 Store)
  // ==================================================================
  // 1. 门口是否堵着一条 Load？
  val incoming_is_load = io.in.valid && !LSUOpType.isStore(io.in.bits.func)
  // 2. 内部是否真的正在处理一条 Load？
  // [关键] 必须同时检查 state 和 func_reg！
  // 只有当状态是等待，且当前指令是 Load 时，才算"内部有 Load"
  val pending_real_load = (state === s_wait_resp) && LSUOpType.isLoad(func_reg)
  // 3. 输出给 Hazard Unit 的信号
  // 只要门口有 Load，或者里面有 Load，就要报警
  io.is_load := incoming_is_load || pending_real_load
  // 4. 目标寄存器选择 (Priority Mux)
  // 逻辑推演：
  // Case A: 内部是 LW (pending_real_load=True) -> 必须报内部的 rd (rdAddr_reg)
  // Case B: 内部是 SW (pending_real_load=False), 门口是 LW -> Mux 选 False 分支 -> 报门口的 rd (io.in.bits.rdAddr)
  // Case C: 内部是 Idle, 门口是 LW -> 同 Case B -> 报门口的 rd
  io.load_rd := Mux(pending_real_load, rdAddr_reg, io.in.bits.rdAddr)

}