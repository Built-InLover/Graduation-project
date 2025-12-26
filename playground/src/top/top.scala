package top

import chisel3._
import chisel3.util._
import corewithbus._
import common._

class DistributedCore extends Module {
  val io = IO(new Bundle {
    //  对接外部系统总线
    val imem_bus = new SimpleBus // 取指总线
    val dmem_bus = new SimpleBus // 访存总线
  })

  //  例化所有分布式模块
  val ifu = Module(new IFU)
  val idu = Module(new IDU)
  val exu = Module(new EXU)
  val lsu = Module(new LSU)
  val wbu = Module(new WBU)
  val rf  = Module(new RegFile)

  // IFU -> IDU: 加入深度为 1 的 Queue，切断组合环路，同时开启pipe，提高IPC
  val ifu_idu_q = Module(new Queue(chiselTypeOf(ifu.io.out.bits), entries = 1, pipe = true))
  ifu.io.out <> ifu_idu_q.io.enq
  ifu_idu_q.io.deq <> idu.io.in

  // IDU -> EXU: 同样可以加一个 Queue，让流水线层级更清晰
  val idu_exu_q = Module(new Queue(chiselTypeOf(idu.io.out.bits), entries = 1, pipe = true))
  idu.io.out <> idu_exu_q.io.enq
  idu_exu_q.io.deq <> exu.io.in

  // 逻辑：(系统全局复位为 1) 或者 (内部跳转冲刷信号为 1)
  // 结果：只要满足其一，Queue 就会被清空
  ifu_idu_q.reset := reset.asBool || idu.io.flush
  idu_exu_q.reset := reset.asBool || idu.io.flush

  // 后续分流部分不需要 Queue，因为 LSU 和 WBU 已经是执行的终点了
  lsu.io.in    <> exu.io.lsuOut
  wbu.io.exuIn <> exu.io.wbuOut
  wbu.io.lsuIn <> lsu.io.out

  //控制信号反馈

  // 跳转控制逻辑：当 EXU 确定要跳转时，跳转信号valid，同时传给IFU新的PC
  val redirect_valid = exu.io.redirect.valid
  ifu.io.redirect := exu.io.redirect
  
  // 冲刷逻辑：发生跳转时，IDU 内正在译码的指令必须作废
  // 注意：IFU 内部通常在收到 redirect 后会自动清空
  idu.io.flush := redirect_valid

  // --- [4] 资源访问连接 (Resource Access) ---

  // 寄存器堆读取 (IDU 侧)
  rf.io.rs1_addr     := idu.io.rf_rs1_addr
  rf.io.rs2_addr     := idu.io.rf_rs2_addr
  idu.io.rf_rs1_data := rf.io.rs1_data
  idu.io.rf_rs2_data := rf.io.rs2_data

  // 寄存器堆写回 (WBU 侧)
  rf.io.wen   := wbu.io.rf_wen
  rf.io.waddr := wbu.io.rf_waddr
  rf.io.wdata := wbu.io.rf_wdata

  // --- [5] 外部接口连接 ---
  io.imem_bus <> ifu.io.bus
  io.dmem_bus <> lsu.io.bus
}