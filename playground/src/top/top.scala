package top

import chisel3._
import chisel3.util._
import corewithbus._
import common._
import essentials._

class DistributedCore extends Module {
  val io = IO(new Bundle {
    // 对接仿真系统 (Verilator 通过 top->io_xxx 直接访问)
    val debug_pc    = Output(UInt(32.W))
    val debug_dnpc  = Output(UInt(32.W))
    val debug_regs  = Output(Vec(32, UInt(32.W)))
    val mtrace_pc   = Output(UInt(32.W))
    val inst_over   = Output(Bool())
    val ebreak      = Output(Bool())
  })

  // ==================================================================
  //                        1. 模块实例化
  // ==================================================================
  val ifu = Module(new IFU)
  val idu = Module(new IDU)
  val exu = Module(new EXU)
  val lsu = Module(new LSU)
  val wbu = Module(new WBU)
  val rf  = Module(new RegFile)
  val mem = Module(new MemSystem)

  // ==================================================================
  //                        2. 调试接口连线 (Debug Interface)
  // ==================================================================
  
  // 基础信号直连
  io.debug_pc   := wbu.io.debug_pc
  io.debug_dnpc := wbu.io.debug_dnpc   
  io.inst_over  := wbu.io.inst_over
  io.ebreak     := wbu.io.ebreak
  io.mtrace_pc  := lsu.io.pc_mtrace

  // [关键] 寄存器堆旁路逻辑 (Debug Bypass Network)
  // 解决 Difftest 时序错位问题：在指令 Commit 当拍直接输出 WBU 写入的新值
  
  val current_rf_wen   = wbu.io.rf_wen
  val current_rf_waddr = wbu.io.rf_waddr
  val current_rf_wdata = wbu.io.rf_wdata

  for (i <- 0 until 32) {
    if (i == 0) {
      io.debug_regs(0) := 0.U // x0 恒为 0
    } else {
      // 如果这一拍正在写 x[i]，直接给新值；否则给 RegFile 里的旧值
      val is_writing_this_reg = current_rf_wen && (current_rf_waddr === i.U)
      io.debug_regs(i) := Mux(is_writing_this_reg, current_rf_wdata, rf.io.regs(i)) 
    }
  }

  // ==================================================================
  //                        3. 流水线级间连接 (Inter-stage Queue)
  // ==================================================================
  // 统一启用pipe=true (满载吞吐)

  // IFU -> IDU
  val ifu_idu_q = Module(new Queue(chiselTypeOf(ifu.io.out.bits), entries = 1, pipe = true))
  ifu.io.out <> ifu_idu_q.io.enq
  ifu_idu_q.io.deq <> idu.io.in

  // IDU -> EXU
  val idu_exu_q = Module(new Queue(chiselTypeOf(idu.io.out.bits), entries = 1, pipe = true))
  idu.io.out <> idu_exu_q.io.enq
  idu_exu_q.io.deq <> exu.io.in

  // EXU -> WBU (EXU计算结果)
  val exu_wbu_q = Module(new Queue(chiselTypeOf(exu.io.wbuOut.bits), entries = 1, pipe = true))
  exu.io.wbuOut <> exu_wbu_q.io.enq
  exu_wbu_q.io.deq <> wbu.io.exuIn

  // EXU -> LSU (访存请求)
  val exu_lsu_q = Module(new Queue(chiselTypeOf(exu.io.lsuOut.bits), entries = 1, pipe = true))
  exu.io.lsuOut <> exu_lsu_q.io.enq
  exu_lsu_q.io.deq <> lsu.io.in

  // LSU -> WBU (访存结果)
  val lsu_wbu_q = Module(new Queue(chiselTypeOf(lsu.io.out.bits), entries = 1, pipe = true))
  lsu.io.out <> lsu_wbu_q.io.enq
  lsu_wbu_q.io.deq <> wbu.io.lsuIn

  // ==================================================================
  //                        4. 冲刷逻辑 (Flush Logic)
  // ==================================================================
  // 当发生跳转时，IFU 和 IDU 里的旧指令（预测失败路径）必须被清除
  // 注意：LSU 和 WBU 里的指令是合法的，不能被冲刷！
  
  ifu_idu_q.reset := reset.asBool || idu.io.flush
  idu_exu_q.reset := reset.asBool || idu.io.flush
  
  // 控制流反馈
  ifu.io.redirect := exu.io.redirect
  idu.io.flush    := exu.io.redirect.valid 

  // ==================================================================
  //                        5. 顺序令牌队列 (Order Queue / ROB Lite)
  // ==================================================================
  // 作用：记录进入 EXU 的指令是 LSU 类还是 EXU 类，指导 WBU 按序写回
  
  val order_q = Module(new Queue(Bool(), entries = 8, pipe = true)) 

  // 1. 入队 (Enq): 谁来写账本？
  // 只要指令成功从 IDU 发往 EXU (idu.io.out.fire)，且未被 Flush，就必须记一笔
  // 注意：Branch/Jump/Store 都要记，因为它们都要去 WBU 报到（或占位）
  val idu_fire = idu.io.out.fire
  val is_lsu   = idu.io.out.bits.fuType === FuType.lsu

  order_q.io.enq.valid := idu_fire && !idu.io.flush 
  order_q.io.enq.bits  := is_lsu

  // 2. 复位逻辑
  // 关键：令牌队列只受全局 Reset 控制，跳转时不清空！
  order_q.reset := reset.asBool

  // 3. 出队 (Deq): 谁来查账本？
  wbu.io.next_is_lsu   := order_q.io.deq.bits
  wbu.io.token_valid   := order_q.io.deq.valid
  order_q.io.deq.ready := wbu.io.token_pop // WBU 每处理完一条就弹出一个

  // ==================================================================
  //                        6. 数据冒险处理 (Forwarding & Load-Use)
  // ==================================================================

  // --- [Forwarding] 收集数据源 ---
  val forward_sources = Seq(
    // (有效位, rd编号, 数据内容)
    (exu.io.wbuOut.valid && exu.io.wbuOut.bits.rfWen, exu.io.wbuOut.bits.rdAddr, exu.io.wbuOut.bits.data),       // EXU 刚算出的
    (exu_wbu_q.io.deq.valid && exu_wbu_q.io.deq.bits.rfWen, exu_wbu_q.io.deq.bits.rdAddr, exu_wbu_q.io.deq.bits.data), // 待写回的 EXU 结果
    (lsu_wbu_q.io.deq.valid && lsu_wbu_q.io.deq.bits.rfWen, lsu_wbu_q.io.deq.bits.rdAddr, lsu_wbu_q.io.deq.bits.rdata) // 待写回的 LSU 结果
    //因为目前的core，exu永远比LSU快，所以如果同时生效，说明LSU肯定是先进流水线但是后完成的，导致EXU被阻塞在这里了，所以LSU更晚，EXU更新
    //倘若后面加入乘除导致EXU不一定比LSU慢的话，那完美的做法是给每个指令发一个 ID，Forwarding 时比较 ID 大小，谁 ID 大谁是新的。
  )

  // 连线到 IDU
  for (i <- 0 until 3) {
    idu.io.forward_in(i).valid  := forward_sources(i)._1
    idu.io.forward_in(i).rdAddr := forward_sources(i)._2
    idu.io.forward_in(i).data   := forward_sources(i)._3
  }

  // --- [Load-Use Hazard] 连线 ---
  // 1. EXU 探测
  val ex_inst_valid = exu.io.in.valid 
  val ex_is_load    = exu.io.in.bits.isLoad
  val ex_rd         = exu.io.in.bits.rdAddr

  idu.io.ex_is_load := ex_inst_valid && ex_is_load
  idu.io.ex_rd_addr := ex_rd

  // 2. LSU 探测 (新增)
  idu.io.lsu_is_load := lsu.io.is_load
  idu.io.lsu_rd_addr := lsu.io.load_rd

  // ==================================================================
  //                        7. 资源与总线连接
  // ==================================================================

  // RegFile
  rf.io.rs1_addr     := idu.io.rf_rs1_addr
  rf.io.rs2_addr     := idu.io.rf_rs2_addr
  idu.io.rf_rs1_data := rf.io.rs1_data
  idu.io.rf_rs2_data := rf.io.rs2_data

  rf.io.wen   := wbu.io.rf_wen
  rf.io.waddr := wbu.io.rf_waddr
  rf.io.wdata := wbu.io.rf_wdata

  // Memory System
  mem.io.ifu_bus <> ifu.io.bus
  mem.io.lsu_bus <> lsu.io.bus
}