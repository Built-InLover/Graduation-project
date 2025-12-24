package core

import chisel3._
import chisel3.util._

object ALUOpType {
  def add  = "b1000000".U
  def sll  = "b0000001".U
  def slt  = "b0000010".U
  def sltu = "b0000011".U
  def xor  = "b0000100".U
  def srl  = "b0000101".U
  def or   = "b0000110".U
  def and  = "b0000111".U
  def sub  = "b0001000".U
  def sra  = "b0001101".U
}

class ALU extends Module {
  val io = IO(new Bundle {
    val in1 = Input(UInt(32.W))  // 第一个操作数
    val in2 = Input(UInt(32.W))  // 第二个操作数
    val opType = Input(UInt(7.W))    // ALU 操作类型
    val result = Output(UInt(32.W))  // 操作结果
  })

  // 实现 ALU 操作
  io.result := MuxCase(0.U,Vector(
    (io.opType === ALUOpType.add)  -> (io.in1 + io.in2),  // 加法
    (io.opType === ALUOpType.sub)  -> (io.in1 - io.in2),  // 减法
    (io.opType === ALUOpType.sll)  -> (io.in1 << io.in2(4, 0)), // 左移
    (io.opType === ALUOpType.srl)  -> (io.in1 >> io.in2(4, 0)), // 右移
    (io.opType === ALUOpType.sra)  -> (io.in1.asSInt >> io.in2(4, 0)).asUInt, // 算术右移
    (io.opType === ALUOpType.xor)  -> (io.in1 ^ io.in2),  // 异或
    (io.opType === ALUOpType.or)   -> (io.in1 | io.in2),  // 或
    (io.opType === ALUOpType.and)  -> (io.in1 & io.in2),  // 与
    (io.opType === ALUOpType.slt)  -> (io.in1.asSInt < io.in2.asSInt).asUInt, // 小于比较
    (io.opType === ALUOpType.sltu) -> (io.in1 < io.in2), // 无符号小于比较
  ))
}
