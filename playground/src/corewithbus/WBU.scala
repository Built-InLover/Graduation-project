package corewithbus

import chisel3._
import chisel3.util._
import essentials._

class WBU extends Module {
  val io = IO(new Bundle {
    // ---------------------------------------------------------
    // 1. 上级流水线输入接口 (Decoupled)
    // ---------------------------------------------------------
    // 来自 EXU 的指令 (ALU, Branch, Jump, CSR, WBU-only)
    val exuIn = Flipped(Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val data   = UInt(32.W)
      val rdAddr = UInt(5.W)
      val rfWen  = Bool()
      val uop_id = UInt(4.W)
      val exception = Valid(UInt(32.W))
    }))
    // 来自 LSU 的指令 (Load/Store)
    val lsuIn = Flipped(Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val rdata  = UInt(32.W)
      val rdAddr = UInt(5.W)
      val rfWen  = Bool()
      val uop_id = UInt(4.W)
      val exception  = Bool()
    }))
    // ---------------------------------------------------------
    // 2. 控制信号输入 (Arbitration)
    // ---------------------------------------------------------
    val next_is_lsu = Input(Bool())
    val token_valid = Input(Bool())
    val token_pop   = Output(Bool())
    // ---------------------------------------------------------
    // 3. 寄存器堆写接口 (Write Back)
    // ---------------------------------------------------------
    val rf_wen   = Output(Bool())
    val rf_waddr = Output(UInt(5.W))
    val rf_wdata = Output(UInt(32.W))
    // ---------------------------------------------------------
    // 4. 异常输出 (Exception Output)
    // ---------------------------------------------------------
    val exc_valid = Output(Bool())
    val exc_cause = Output(UInt(32.W))
    val exc_pc    = Output(UInt(32.W))
    // ---------------------------------------------------------
    // 5. 调试接口 (Difftest / DPI)
    // ---------------------------------------------------------
    val debug_pc   = Output(UInt(32.W))
    val debug_dnpc = Output(UInt(32.W))
    val inst_over  = Output(Bool())
  })
  // ==================================================================
  //                        逻辑实现 (Logic)
  // ==================================================================
  // ------------------------------------------------------------------
  // [1] 仲裁逻辑 (Arbitration)
  //     基于 Order Queue 的令牌决定当前允许谁握手，解决写回冲突与乱序问题。
  // ------------------------------------------------------------------
  // 判定当前 Order Queue 指示的指令类型
  val wait_lsu = io.token_valid && io.next_is_lsu  // 队首是 LSU 指令
  val wait_exu = io.token_valid && !io.next_is_lsu // 队首是 EXU 指令
  // 驱动 Ready 信号 (反压控制)
  // 只有拿到对应令牌的通道才允许握手，彻底抛弃 lsu_busy 依赖
  io.lsuIn.ready := wait_lsu
  io.exuIn.ready := wait_exu
  // ------------------------------------------------------------------
  // [2] 提交判定 (Commit Detection)
  // ------------------------------------------------------------------
  val lsu_fire = io.lsuIn.fire
  val exu_fire = io.exuIn.fire
  // 任意一方握手成功，即视为有一条指令完成退休
  val inst_commit = lsu_fire || exu_fire

  // ------------------------------------------------------------------
  // [2.5] 异常检测 (Exception Detection)
  // ------------------------------------------------------------------
  val exu_exc = exu_fire && io.exuIn.bits.exception.valid
  val lsu_exc = lsu_fire && io.lsuIn.bits.exception
  val has_exc = exu_exc || lsu_exc

  io.exc_valid := has_exc
  io.exc_pc    := Mux(lsu_fire, io.lsuIn.bits.pc, io.exuIn.bits.pc)
  io.exc_cause := Mux(lsu_exc,
    Mux(io.lsuIn.bits.rfWen, CauseCode.LOAD_ACCESS_FAULT, CauseCode.STORE_ACCESS_FAULT),
    io.exuIn.bits.exception.bits)
  // ------------------------------------------------------------------
  // [3] 令牌管理 (Token Management)
  // ------------------------------------------------------------------
  // 只要完成一条指令，就通知 Order Queue 弹出一个令牌，准备处理下一条
  io.token_pop := inst_commit
  // ------------------------------------------------------------------
  // [4] 寄存器堆写回逻辑 (RegFile Write Logic)
  //     根据谁发生了 Fire 来选择数据源
  // ------------------------------------------------------------------
  val final_rf_wen   = Mux(lsu_fire, io.lsuIn.bits.rfWen,  io.exuIn.bits.rfWen)
  val final_rf_waddr = Mux(lsu_fire, io.lsuIn.bits.rdAddr, io.exuIn.bits.rdAddr)
  val final_rf_wdata = Mux(lsu_fire, io.lsuIn.bits.rdata,  io.exuIn.bits.data)
  // 最终写使能：必须 Commit + 无异常 + 指令请求写回 + 目标不是 x0
  io.rf_wen   := inst_commit && !has_exc && final_rf_wen && (final_rf_waddr =/= 0.U)
  io.rf_waddr := final_rf_waddr
  io.rf_wdata := final_rf_wdata
  // ------------------------------------------------------------------
  // [5] 调试与差异测试接口 (Debug / Difftest Interface)
  //     纯组合逻辑输出，与 inst_commit 同步
  // ------------------------------------------------------------------
  // 默认赋值 (防止锁存器推断)
  io.inst_over  := inst_commit
  io.debug_pc   := 0.U
  io.debug_dnpc := 0.U
  // 当发生提交时，输出对应的指令信息
  when(inst_commit) {
    // 优先级选择：Token 机制保证了 lsu_fire 和 exu_fire 互斥
    // 因此这里可以用 Mux 选取正在提交的那一路数据
    io.debug_pc   := Mux(lsu_fire, io.lsuIn.bits.pc,   io.exuIn.bits.pc)
    io.debug_dnpc := Mux(lsu_fire, io.lsuIn.bits.dnpc, io.exuIn.bits.dnpc)
  }
}
// --- 顺便把 RegFile 也定义了 ---
class RegFile extends Module {
  val io = IO(new Bundle {
    val rs1_addr = Input(UInt(5.W))
    val rs1_data = Output(UInt(32.W))
    val rs2_addr = Input(UInt(5.W))
    val rs2_data = Output(UInt(32.W))
    val regs     = Output(Vec(32, UInt(32.W)))
    val wen      = Input(Bool())
    val waddr    = Input(UInt(5.W))
    val wdata    = Input(UInt(32.W))
  })
  val regs = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
  io.regs := regs
  // 组合逻辑读
  io.rs1_data := Mux(io.rs1_addr === 0.U, 0.U, regs(io.rs1_addr))
  io.rs2_data := Mux(io.rs2_addr === 0.U, 0.U, regs(io.rs2_addr))
  // 时钟上升沿写
  when(io.wen) {
    regs(io.waddr) := io.wdata
  }
}