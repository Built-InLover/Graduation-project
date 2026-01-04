package corewithbus

import chisel3._
import chisel3.util._

class WBU extends Module {
  val io = IO(new Bundle {
    // 1. 来自 EXU 的写回请求 (带 rfWen)
    val exuIn = Flipped(Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val data   = UInt(32.W)
      val rdAddr = UInt(5.W)
      val rfWen  = Bool()
      val is_csr = Bool()
    }))

    // 2. 来自 LSU 的写回请求 (带 rfWen)
    val lsuIn = Flipped(Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val rdata  = UInt(32.W)
      val rdAddr = UInt(5.W)
      val rfWen  = Bool()
      val lsu_busy = Bool()
    }))

    // 3. 对接 RegFile 的写端口
    val rf_wen   = Output(Bool())
    val rf_waddr = Output(UInt(5.W))
    val rf_wdata = Output(UInt(32.W))

    //对接顶层和 DPI 的调试接口 ---
    val debug_pc   = Output(UInt(32.W))
    val debug_dnpc = Output(UInt(32.W))
    val inst_over  = Output(Bool())
    val ebreak     = Output(Bool())
  })
// 1. 定义锁存寄存器
  val debug_pc_reg   = RegInit(0.U(32.W))
  val debug_dnpc_reg = RegInit(0.U(32.W))
  val ebreak_reg     = RegInit(false.B)
  val inst_over_reg     = RegInit(false.B)

  io.debug_pc   := debug_pc_reg
  io.debug_dnpc := debug_dnpc_reg
  io.ebreak     := ebreak_reg
  io.inst_over  := inst_over_reg

// --- [1. 确定谁有资格在这一拍“退休” (Commit)] ---
  
  // LSU 优先级最高：只要 lsuIn.valid 亮了，这一拍就是给 LSU 的
  val lsu_commit = io.lsuIn.valid
  
  // EXU 提交的先决条件：
  // 1. EXU 本身有指令 (exuIn.valid)
  // 2. LSU 当前没有正在处理的指令 (lsu_busy 为假)
  // 3. LSU 这一拍也没有刚好完成 (lsu_commit 为假) —— 避免同一拍出两条指令
  val exu_fire = !io.lsuIn.bits.lsu_busy && !lsu_commit
  val exu_commit = io.exuIn.valid && exu_fire

  // --- [2. 驱动 Ready 信号 (反向压力)] ---
  
  io.lsuIn.ready := true.B // LSU 只要数据回来，WBU 必须接着
  // 只有当 LSU 彻底空闲时，才允许 EXU 的数据进入 WBU
  io.exuIn.ready := exu_fire 

  // --- [3. 驱动调试接口 (Difftest)] ---
  
  // 只有真正被允许 commit 的时候才拉高 inst_over
  val inst_commit  = lsu_commit || exu_commit

    // 3. 核心：只有在 commit 发生时，才更新调试 PC 寄存器
  when(inst_commit) {
    debug_pc_reg   := Mux(lsu_commit, io.lsuIn.bits.pc,   io.exuIn.bits.pc)
    debug_dnpc_reg := Mux(lsu_commit, io.lsuIn.bits.dnpc, io.exuIn.bits.dnpc)
    // ebreak 也要锁存，确保和这一条指令的 PC 同步出现
    ebreak_reg     := exu_commit && io.exuIn.bits.is_csr && (io.exuIn.bits.data === 0x1.U)
  }.otherwise {
    ebreak_reg := false.B
  }
  inst_over_reg := inst_commit

  // --- [4. 驱动 RegFile 写回] ---
  
  // 选中的数据源
  // 这里可以保持组合逻辑，因为 RegFile 内部本身是在时钟上升沿根据 io.rf_wen 写的
  val final_rf_wen   = Mux(lsu_commit, io.lsuIn.bits.rfWen,  io.exuIn.bits.rfWen)
  val final_rf_waddr = Mux(lsu_commit, io.lsuIn.bits.rdAddr, io.exuIn.bits.rdAddr)
  val final_rf_wdata = Mux(lsu_commit, io.lsuIn.bits.rdata,  io.exuIn.bits.data)

  // 最终写使能：必须是有人成功 commit，且该指令需要写回，且不是 x0
  io.rf_wen   := inst_commit && final_rf_wen && (final_rf_waddr =/= 0.U)
  io.rf_waddr := final_rf_waddr
  io.rf_wdata := final_rf_wdata
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
