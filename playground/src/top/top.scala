package top

import chisel3._
import chisel3.util._
import corewithbus._
import common._

class DistributedCore extends Module {
  val io = IO(new Bundle {
    // 对接仿真系统 (Verilator 通过 top->io_xxx 直接访问)
    val debug_pc    = Output(UInt(32.W))
    val debug_dnpc  = Output(UInt(32.W))
    val debug_regs  = Output(Vec(32, UInt(32.W)))
    val inst_over   = Output(Bool())
    val ebreak      = Output(Bool())
  })

  // --- [1. 例化核心模块] ---
  val ifu = Module(new IFU)
  val idu = Module(new IDU)
  val exu = Module(new EXU)
  val lsu = Module(new LSU)
  val wbu = Module(new WBU)
  val rf  = Module(new RegFile)
  val mem = Module(new MemSystem)

  // --- [2. 驱动顶层 Debug 接口] ---
  io.debug_pc   := wbu.io.debug_pc
  io.debug_dnpc := wbu.io.debug_dnpc
  io.debug_regs := rf.io.regs    
  io.inst_over  := wbu.io.inst_over
  io.ebreak     := wbu.io.ebreak

  // --- [3. 流水线级间连接与 Queue 缓冲] ---
  
  // IFU -> IDU: 加入深度为 1 的 Queue，切断组合环路
  val ifu_idu_q = Module(new Queue(chiselTypeOf(ifu.io.out.bits), entries = 1, pipe = true))
  ifu.io.out <> ifu_idu_q.io.enq
  ifu_idu_q.io.deq <> idu.io.in

  // IDU -> EXU: 同样加一个 Queue
  val idu_exu_q = Module(new Queue(chiselTypeOf(idu.io.out.bits), entries = 1, pipe = true))
  idu.io.out <> idu_exu_q.io.enq
  idu_exu_q.io.deq <> exu.io.in

  // 冲刷逻辑：发生跳转时清空 Queue
  ifu_idu_q.reset := reset.asBool || idu.io.flush
  idu_exu_q.reset := reset.asBool || idu.io.flush

  // 分流：EXU 指令去往 LSU 或直接去往 WBU
  lsu.io.in    <> exu.io.lsuOut
  wbu.io.exuIn <> exu.io.wbuOut
  wbu.io.lsuIn <> lsu.io.out

  // --- [4. 控制流反馈] ---
  ifu.io.redirect := exu.io.redirect
  idu.io.flush    := exu.io.redirect.valid // 当跳转发生时冲刷流水线

  // --- [5. 资源访问 (RegFile)] ---
  rf.io.rs1_addr     := idu.io.rf_rs1_addr
  rf.io.rs2_addr     := idu.io.rf_rs2_addr
  idu.io.rf_rs1_data := rf.io.rs1_data
  idu.io.rf_rs2_data := rf.io.rs2_data

  rf.io.wen   := wbu.io.rf_wen
  rf.io.waddr := wbu.io.rf_waddr
  rf.io.wdata := wbu.io.rf_wdata

  // --- [6. 内存系统总线连接] ---
  mem.io.ifu_bus <> ifu.io.bus
  mem.io.lsu_bus <> lsu.io.bus
}