package corewithbus

import chisel3._
import chisel3.util._
import essentials._
import common._

class IDU extends Module with HasInstrType {
  val io = IO(new Bundle {
    // 1. 基础输入接口 (来自 IFU)
    val in = Flipped(Decoupled(new Bundle {
      val inst = UInt(32.W)
      val pc = UInt(32.W)
    }))
    // 2. 解耦输出接口 (发往 EXU)
    val out = Decoupled(new Bundle {
      val pc = UInt(32.W)
      val src1 = UInt(32.W)
      val src2 = UInt(32.W)
      val imm = UInt(32.W)
      val fuType = FuType()
      val fuOp = FuOpType()
      val rfWen = Bool()
      val rdAddr = UInt(5.W)
      val isLoad = Bool()
      val isStore = Bool()
      val isBranch = Bool()
      val isJump = Bool()
      val useImm = Bool()
    })
    // 3. 数据冒险处理接口
    val forward_in = Input(Vec(3, new ForwardingBus)) // 旁路信号输入
    val ex_is_load = Input(Bool()) // 上一条指令(EXU阶段)是否为Load
    val ex_rd_addr = Input(UInt(5.W)) // 上一条指令(EXU阶段)的目标寄存器
    val lsu_is_load = Input(Bool()) // 上一条指令(LSU阶段)是否为Load
    val lsu_rd_addr = Input(UInt(5.W)) // 上一条指令(LSU阶段)的目标寄存器
    val flush = Input(Bool()) // 流水线冲刷
    // 4. Register File 读接口
    val rf_rs1_addr = Output(UInt(5.W))
    val rf_rs2_addr = Output(UInt(5.W))
    val rf_rs1_data = Input(UInt(32.W))
    val rf_rs2_data = Input(UInt(32.W))
  })
  // 提取指令与 PC
  val inst = io.in.bits.inst
  val pc = io.in.bits.pc
  // ==================================================================
  //                        1. 译码与控制信号生成
  // ==================================================================
  // --- 指令译码 (Lookup Table) ---
  val defaultSignals = List(InstrN, FuType.alu, 0.U(7.W))
  val decodeRes = ListLookup(inst, defaultSignals, RVIInstr.table ++ Privileged.table)
  val instrType = decodeRes(0)
  val fuType = decodeRes(1)
  val fuOp = decodeRes(2)
  // --- 立即数生成 (Immediate Generation) ---
  val sign_bit = inst(31)
  val i_imm_raw = inst(31, 20)
  val s_imm_raw = Cat(inst(31, 25), inst(11, 7))
  val b_imm_raw = Cat(inst(31), inst(7), inst(30, 25), inst(11, 8))
  val u_imm_raw = inst(31, 12)
  val j_imm_raw = Cat(inst(31), inst(19, 12), inst(20), inst(30, 21))
  val final_imm = MuxLookup(instrType, 0.U)(
    Seq(
      InstrI -> Cat(Fill(20, sign_bit), i_imm_raw),
      InstrS -> Cat(Fill(20, sign_bit), s_imm_raw),
      InstrB -> Cat(Fill(19, sign_bit), b_imm_raw, 0.U(1.W)),
      InstrU -> Cat(u_imm_raw, 0.U(12.W)),
      InstrJ -> Cat(Fill(11, sign_bit), j_imm_raw, 0.U(1.W))
    )
  )
  // --- 寄存器地址提取 ---
  io.rf_rs1_addr := inst(19, 15)
  io.rf_rs2_addr := inst(24, 20)
  val rd_addr = inst(11, 7)
// -----------------------------------------------------------------
  //            2 [Load-Use 冒险检测逻辑 - 终极版]
  // -----------------------------------------------------------------
  // 1. 确定当前 IDU 指令（消费者）到底需要读哪些寄存器？
  //    这步必须非常精确，不能漏掉 Store 的 rs2 (数据) 和 Load 的 rs1 (地址)
  val is_r_type = (instrType === InstrR)
  val is_i_type = (instrType === InstrI)
  val is_s_type = (instrType === InstrS) // Store
  val is_b_type = (instrType === InstrB) // Branch
  val is_j_type = (instrType === InstrJ)
  val is_u_type = (instrType === InstrU)
  val idu_valid = io.in.valid && !io.flush

  // rs1 使用情况：
  // R-Type (ADD), I-Type (ADDI/LW), S-Type (SW), B-Type (BEQ) 都要读 rs1
  // 只有 U-Type (LUI/AUIPC) 和 J-Type (JAL) 不读 rs1
  val uses_rs1 = idu_valid && (is_r_type || is_i_type || is_s_type || is_b_type)

  // rs2 使用情况：
  // R-Type (ADD), S-Type (SW - 存数据的那个寄存器!), B-Type (BEQ) 都要读 rs2
  // I-Type (LW/ADDI) 不读 rs2
  val uses_rs2 = idu_valid && (is_r_type || is_s_type || is_b_type)

  // 2. 确定上游（生产者）是否有 Load 指令在运行？
  //    Check 1: EXU 阶段是否是 Load
  val ex_load_hazard = io.ex_is_load && (io.ex_rd_addr =/= 0.U)
  //    Check 2: LSU 阶段是否是 Load (正在等内存)
  val lsu_load_hazard = io.lsu_is_load && (io.lsu_rd_addr =/= 0.U)
  
  // 3. 碰撞检测 (Collision Detection)
  // 针对 rs1 的冲突
  val conflict_rs1 = uses_rs1 && (
    (ex_load_hazard && io.rf_rs1_addr === io.ex_rd_addr) || // 撞上 EXU
    (lsu_load_hazard && io.rf_rs1_addr === io.lsu_rd_addr)  // 撞上 LSU
  )
  // 针对 rs2 的冲突 (这里抓住了你的 SW Bug!)
  // 对于 SW 指令，rf_rs2_addr 就是要存入内存的数据寄存器。
  // 如果这个数据是上一条 LW 产生的，这里必须 Stall。
  val conflict_rs2 = uses_rs2 && (
    (ex_load_hazard && io.rf_rs2_addr === io.ex_rd_addr) || // 撞上 EXU
    (lsu_load_hazard && io.rf_rs2_addr === io.lsu_rd_addr) // 撞上 LSU
  )
  
  // 4. 最终裁决
  val load_use_stall = conflict_rs1 || conflict_rs2
  // ==================================================================
  //                        3. 数据准备 (Forwarding & Mux)
  // ==================================================================
  // --- 前向传递处理 (Forwarding Logic) ---
  val rs1_final = ForwardingUnit(io.rf_rs1_addr, io.rf_rs1_data, io.forward_in)
  val rs2_final = ForwardingUnit(io.rf_rs2_addr, io.rf_rs2_data, io.forward_in)
  // --- 操作数选择 ---
  // src1 特殊处理：AUIPC/JAL(选PC), LUI(选Zero), 其他(选修正后的rs1)
  val src1IsPC = (instrType === InstrU && fuOp === ALUOpType.auipc) || (instrType === InstrJ)
  val src1IsZero = (instrType === InstrU && fuOp === ALUOpType.lui)
  val src1_out = MuxCase(rs1_final, Seq(
      src1IsPC   -> pc,
      src1IsZero -> 0.U(32.W)
    )
  )
  val src2_out = rs2_final
  // ==================================================================
  //                        4. 输出赋值
  // ==================================================================
  io.out.bits.pc := pc
  io.out.bits.src1 := src1_out
  io.out.bits.src2 := src2_out
  io.out.bits.imm := final_imm
  io.out.bits.fuType := fuType
  io.out.bits.fuOp := fuOp
  io.out.bits.rdAddr := rd_addr
  io.out.bits.rfWen := isrfWen(instrType)
  // 辅助控制信号
  io.out.bits.isLoad := (fuType === FuType.lsu) && LSUOpType.isLoad(fuOp)
  io.out.bits.isStore := (fuType === FuType.lsu) && LSUOpType.isStore(fuOp)
  io.out.bits.isBranch := (fuType === FuType.bru) && (instrType === InstrB)
  io.out.bits.isJump := (instrType === InstrJ || (instrType === InstrI && fuType === FuType.bru))
  io.out.bits.useImm := (instrType === InstrI || instrType === InstrS ||
    instrType === InstrU || instrType === InstrJ)
  // ==================================================================
  //                        5. 握手与流水线控制
  // ==================================================================
  // 1. in.ready (反压 IFU):
  //    只有当 EXU 准备好接收，且当前没有发生 Load-Use Stall 时，IDU 才准备好接收新指令
  io.in.ready := io.out.ready && !load_use_stall
  // 2. out.valid (发送给 EXU):
  //    输入有效，且没有 Flush，且没有发生 Load-Use Stall (Stall 时发送气泡)
  io.out.valid := io.in.valid && !io.flush && !load_use_stall
}