package corewithbus

import chisel3._
import chisel3.util._
import essentials._
import common._

class IDU extends Module with HasInstrType {
  val io = IO(new Bundle {
    // 1. 基础输入接口 (来自 IFU)
    val in = Flipped(Decoupled(new Bundle {
      val inst  = UInt(32.W)
      val pc    = UInt(32.W)
      val fault = Bool()
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
      val uop_id = UInt(4.W) // [新增] 指令身份证
    })
    // 3. 数据冒险处理接口
    val forward_in = Input(Vec(8, new ForwardingBus))  // 旁路信号输入

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
  val decodeRes = ListLookup(inst, defaultSignals, RVIInstr.table ++ RVMInstr.table ++ Privileged.table ++ RVZicsrInstr.table)
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
// ==================================================================
  //                1. ID 生成 (Uop ID Generation)
  // ==================================================================
  // 简单的 4位 计数器，溢出自动回绕，符合环形距离计算要求
  val uop_counter = RegInit(0.U(4.W))
  when(io.out.fire) {
    uop_counter := uop_counter + 1.U
  }

  // ==================================================================
  //                2. 智能旁路与暂停 (Forwarding & Stall)
  // ==================================================================
  
  // 调用你的新工具 ForwardingChoose
  // 它会返回：1. 最新的数据  2. 是否需要 Stall (命中 pend 但 valid=0)
  val (rs1_fwd_val, rs1_stall_req) = ForwardingChoose(uop_counter, io.rf_rs1_addr, io.rf_rs1_data, io.forward_in)
  val (rs2_fwd_val, rs2_stall_req) = ForwardingChoose(uop_counter, io.rf_rs2_addr, io.rf_rs2_data, io.forward_in)

  // 辅助判断：当前指令是否真的需要读这两个寄存器？
  // 比如 LUI 虽然 rs1_addr 字段非零，但它其实不读 rs1，所以不需要 Stall
  val is_r_type = (instrType === InstrR)
  val is_i_type = (instrType === InstrI)
  val is_s_type = (instrType === InstrS)
  val is_b_type = (instrType === InstrB)
  
  val idu_valid = io.in.valid && !io.flush
  val uses_rs1  = idu_valid && (is_r_type || is_i_type || is_s_type || is_b_type)
  val uses_rs2  = idu_valid && (is_r_type || is_s_type || is_b_type)

  // 最终暂停信号：需要读 且ForwardingUnit说要暂停
  val load_use_stall = (uses_rs1 && rs1_stall_req) || (uses_rs2 && rs2_stall_req)

  // ==================================================================
  //                3. 操作数选择
  // ==================================================================
  val src1IsPC   = (instrType === InstrU && fuOp === ALUOpType.auipc) || (instrType === InstrJ)
  val src1IsZero = (instrType === InstrU && fuOp === ALUOpType.lui)
  
  // 直接使用 Forwarding 后的数据
  val src1_out = MuxCase(rs1_fwd_val, Seq(
      src1IsPC   -> pc,
      src1IsZero -> 0.U(32.W)
  ))
  val src2_out = rs2_fwd_val

  // ==================================================================
  //                4. 输出赋值
  // ==================================================================
  val ifu_fault = io.in.bits.fault

  io.out.bits.pc     := pc
  io.out.bits.uop_id := uop_counter
  io.out.bits.src1   := src1_out
  io.out.bits.src2   := src2_out
  io.out.bits.imm    := Mux(ifu_fault, 2.U, final_imm) // fault → imm=2 作为 inst_access_fault 标记
  io.out.bits.fuType := Mux(ifu_fault, FuType.csr, fuType)
  io.out.bits.fuOp   := Mux(ifu_fault, CSROpType.jmp, fuOp)
  io.out.bits.rdAddr := Mux(ifu_fault, 0.U, rd_addr)
  io.out.bits.rfWen  := Mux(ifu_fault, false.B, isrfWen(instrType))
  
  // 辅助位
  io.out.bits.isLoad   := !ifu_fault && (fuType === FuType.lsu) && LSUOpType.isLoad(fuOp)
  io.out.bits.isStore  := !ifu_fault && (fuType === FuType.lsu) && LSUOpType.isStore(fuOp)
  io.out.bits.isBranch := !ifu_fault && (fuType === FuType.bru) && (instrType === InstrB)
  io.out.bits.isJump   := !ifu_fault && (instrType === InstrJ || (instrType === InstrI && fuType === FuType.bru))
  io.out.bits.useImm   := ifu_fault || (instrType === InstrI || instrType === InstrS || instrType === InstrU || instrType === InstrJ)

  // ==================================================================
  //                5. 握手
  // ==================================================================
  io.in.ready  := io.out.ready && !load_use_stall
  io.out.valid := io.in.valid && !io.flush && !load_use_stall
}