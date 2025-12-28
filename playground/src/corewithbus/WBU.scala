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


  val lsu_fire = io.lsuIn.valid // 这就是你想要的那个“包裹信号”
  val exu_fire = io.exuIn.valid

  io.inst_over  := lsu_fire || exu_fire
  io.debug_pc   := Mux(lsu_fire, io.lsuIn.bits.pc, io.exuIn.bits.pc)
  io.debug_dnpc := Mux(lsu_fire, io.lsuIn.bits.dnpc, io.exuIn.bits.dnpc)
  io.ebreak     := exu_fire && io.exuIn.bits.is_csr && io.inst_over

  // --- [仲裁逻辑：LSU 优先] ---
  val lsu_request = io.lsuIn.valid
  val exu_request = io.exuIn.valid && !lsu_request

  // 设置 Ready 信号 (背压)
  io.lsuIn.ready := true.B           // WBU 总是能处理 LSU (因为 LSU 结果是异步返回的)
  io.exuIn.ready := !lsu_request     // 如果 LSU 占用了写端口，EXU 必须等待

  // 选中的源
  val selected_rdAddr = Mux(lsu_request, io.lsuIn.bits.rdAddr, io.exuIn.bits.rdAddr)
  val selected_data   = Mux(lsu_request, io.lsuIn.bits.rdata,   io.exuIn.bits.data)
  val selected_rfWen  = Mux(lsu_request, io.lsuIn.bits.rfWen,  io.exuIn.bits.rfWen)

  // --- [驱动 RegFile] ---
  // 最终写使能 = (有人请求) AND (指令本身需要写) AND (不是 x0)
  io.rf_wen   := (lsu_request || exu_request) && selected_rfWen && (selected_rdAddr =/= 0.U)
  io.rf_waddr := selected_rdAddr
  io.rf_wdata := selected_data
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
