package top

import chisel3._
import chisel3.util._
import corewithbus._
import common._

class RK_DPI extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock     = Input(Clock())
    val inst_over = Input(Bool())
    val pc        = Input(UInt(32.W))
    val dnpc      = Input(UInt(32.W))
    val regs      = Input(UInt(1024.W))
    val ebreak    = Input(Bool()) // 对应 WBU 引出的 ebreak
  })
  setInline("RK_DPI.v",
    s"""
     |// 声明外部 C++ 函数
     |import "DPI-C" function void set_riscv_regs(input logic[31:0] regs [32]);
     |import "DPI-C" function void set_pc(input int pc_val);
     |import "DPI-C" function void set_dnpc(input int dnpc_val);
     |import "DPI-C" function void check_commit(input bit over);
     |import "DPI-C" function void trap_handler(input int pc); // 处理 ebreak
     |
     |module RK_DPI(
     |  input          clock,
     |  input          inst_over,
     |  input          ebreak,
     |  input  [31:0]  pc,
     |  input  [31:0]  dnpc,
     |  input  [1023:0] regs
     |);
     |
     |  // 1. 定义逻辑数组供 DPI 使用 (Unpacked Array)
     |  logic [31:0] regs_array [31:0];
     |
     |  // 2. 将 1024 位打包信号切开，填充到数组中
     |  genvar i;
     |  generate
     |    for (i = 0; i < 32; i = i + 1) begin : slice_regs
     |      assign regs_array[i] = regs[i*32 +: 32];
     |    end
     |  endgenerate
     |
     |  // 3. 状态同步逻辑
     |  always @(posedge clock) begin
     |    if (inst_over) begin
     |      set_pc(pc);
     |      set_dnpc(dnpc);
     |      set_riscv_regs(regs_array); // 关键修复：传入处理后的数组
     |      check_commit(1'b1);
     |
     |      // 如果当前退休的是 ebreak，触发 C++ 结束逻辑
     |      if (ebreak) begin
     |        trap_handler(pc);
     |      end
     |    end else begin
     |      check_commit(1'b0);
     |    end
     |  end
     |
     |endmodule
  """.stripMargin)
}
class DistributedCore extends Module {
  val io = IO(new Bundle {
    //  对接仿真系统
    val debug_pc    = Output(UInt(32.W))
    val debug_dnpc    = Output(UInt(32.W))
    val debug_regs  = Output(Vec(32, UInt(32.W)))
    val inst_over   = Output(Bool())
    val ebreak = Output(Bool())
  })

 // --- [例化模块] ---
  val ifu = Module(new IFU)
  val idu = Module(new IDU)
  val exu = Module(new EXU)
  val lsu = Module(new LSU)
  val wbu = Module(new WBU)
  val rf  = Module(new RegFile)
  val dpi = Module(new RK_DPI)
  val mem = Module(new MemSystem)

// 1. 先从源头获取信号，连给顶层 io
  // 注意：wbu 后面的信号现在都属于 io 束
  io.debug_pc   := wbu.io.debug_pc
  io.debug_dnpc := wbu.io.debug_dnpc
  io.debug_regs := rf.io.regs    
  io.inst_over  := wbu.io.inst_over
  io.ebreak     := wbu.io.ebreak

  // 2. 连给 DPI 模块
  // 建议直接连 io.xxx，这样逻辑更清晰，也避免了 Chisel 的连接检查报错
  dpi.io.clock     := clock
  dpi.io.inst_over := io.inst_over
  dpi.io.pc        := io.debug_pc
  dpi.io.dnpc      := io.debug_dnpc
  dpi.io.regs      := io.debug_regs.asUInt
  dpi.io.ebreak    := io.ebreak

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
  mem.io.ifu_bus <> ifu.io.bus
  mem.io.lsu_bus <> lsu.io.bus
}
