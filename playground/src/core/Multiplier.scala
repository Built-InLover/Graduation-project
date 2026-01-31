package core

import chisel3._
import chisel3.util._
import essentials._ 

class Multiplier extends Module {
  val io = IO(new Bundle {
    // [标准输入]：包含计算所需的所有信息
    val in = Flipped(Decoupled(new Bundle {
      val src1   = UInt(32.W)
      val src2   = UInt(32.W)
      val fuOp   = FuOpType()
      val uop_id = UInt(4.W)
      val rdAddr = UInt(5.W) // 穿透传递
      val rfWen  = Bool()    // 穿透传递
      val pc     = UInt(32.W) // [新增]
    }))

    // [标准输出]：包含写回所需的所有信息
    val out = Decoupled(new Bundle {
      val data   = UInt(32.W)
      val uop_id = UInt(4.W)
      val rdAddr = UInt(5.W)
      val rfWen  = Bool()
      val pc     = UInt(32.W) // [新增]
    })
    
    // [状态上报]：给 IDU Forwarding 使用
    val pending = Output(new Bundle {
      val busy = Bool()
      val rd   = UInt(5.W)
      val id   = UInt(4.W)
    })
  })

  // ==================================================================
  // 1. 符号处理与计算 (Combinational Logic)
  // ==================================================================
  val is_rs1_signed = io.in.bits.fuOp === MDUOpType.mulh || io.in.bits.fuOp === MDUOpType.mulhsu
  val is_rs2_signed = io.in.bits.fuOp === MDUOpType.mulh
  
  // 扩展至 64 位
  val rs1_high = Mux(is_rs1_signed && io.in.bits.src1(31), Fill(32, 1.U), 0.U(32.W))
  val rs1_64   = Cat(rs1_high, io.in.bits.src1).asSInt

  val rs2_high = Mux(is_rs2_signed && io.in.bits.src2(31), Fill(32, 1.U), 0.U(32.W))
  val rs2_64   = Cat(rs2_high, io.in.bits.src2).asSInt

  // 核心乘法 (DSP Mapping)
  val product    = rs1_64 * rs2_64 
  val product_64 = product(63, 0).asUInt

  // ==================================================================
  // 2. 流水线控制 (Pipeline Control with Backpressure)
  // ==================================================================
  // 状态位：指示寄存器里的数据是否有效
  val state_valid = RegInit(false.B)
  
  // 流动条件：下游能收 (ready) 或者 当前寄存器无效 (bubble)
  val pipeline_en = io.out.ready || !state_valid

  // 内部流水线寄存器
  val res_reg = Reg(UInt(32.W)) // 存低位结果
  val res_hi  = Reg(UInt(32.W)) // 存高位结果
  val op_reg  = Reg(FuOpType())
  val id_reg  = Reg(UInt(4.W))
  val rd_reg  = Reg(UInt(5.W))
  val wen_reg = Reg(Bool())
  val pc_reg  = Reg(UInt(32.W)) // [新增]

  // 输入 Ready 控制
  io.in.ready := pipeline_en

  when (pipeline_en) {
    state_valid := io.in.valid // 如果 pipeline 动了，更新 valid 状态
    
    // 只有当有新数据进来时，才更新数据寄存器 (节省功耗)
    when (io.in.valid) {
      res_reg := product_64(31, 0)
      res_hi  := product_64(63, 32)
      op_reg  := io.in.bits.fuOp
      id_reg  := io.in.bits.uop_id
      rd_reg  := io.in.bits.rdAddr
      wen_reg := io.in.bits.rfWen
      pc_reg  := io.in.bits.pc // [新增] 锁存 PC
    }
  }

  // ==================================================================
  // 3. 输出逻辑
  // ==================================================================
  // 根据 Op 类型选择输出高位还是低位
  val is_low_part = op_reg === MDUOpType.mul
  
  io.out.valid       := state_valid
  io.out.bits.data   := Mux(is_low_part, res_reg, res_hi)
  io.out.bits.uop_id := id_reg
  io.out.bits.rdAddr := rd_reg
  io.out.bits.rfWen  := wen_reg
  io.out.bits.pc     := pc_reg // [新增] 输出 PC

  // ==================================================================
  // 4. Pending 状态
  // ==================================================================
  // 只要有输入请求，或者内部寄存器有有效数据且没发出去，都算 busy
  io.pending.busy := io.in.valid || (state_valid && !io.out.ready)
  io.pending.rd   := io.in.bits.rdAddr
  io.pending.id   := io.in.bits.uop_id
}