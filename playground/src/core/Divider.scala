package core

import chisel3._
import chisel3.util._
import essentials._

class Divider extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new Bundle {
      val src1   = UInt(32.W)
      val src2   = UInt(32.W)
      val fuOp   = FuOpType()
      val uop_id = UInt(4.W)
      val rdAddr = UInt(5.W)
      val rfWen  = Bool()
      val pc     = UInt(32.W)
    }))
    
    val out = Decoupled(new Bundle {
      val data   = UInt(32.W)
      val uop_id = UInt(4.W)
      val rdAddr = UInt(5.W)
      val rfWen  = Bool()
      val pc     = UInt(32.W)
    })

    val pending = Output(new Bundle {
      val busy = Bool()
      val rd   = UInt(5.W)
      val id   = UInt(4.W)
    })
  })

  // 默认输出赋值
  io.in.ready        := false.B
  io.out.valid       := false.B
  io.out.bits.data   := 0.U
  io.out.bits.uop_id := 0.U
  io.out.bits.rdAddr := 0.U
  io.out.bits.rfWen  := false.B
  io.out.bits.pc     := 0.U 

  val s_idle :: s_div :: s_done :: Nil = Enum(3)
  val state = RegInit(s_idle)

  // 核心寄存器定义：
  // [64:32] (33 bits) -> 当前余数 (Remainder)
  // [31:0]  (32 bits) -> 当前商/剩余被除数 (Quotient)
  // 总共 65 bits
  val shift_reg = Reg(UInt(65.W)) 
  val divisor   = Reg(UInt(33.W)) 
  val count     = Reg(UInt(6.W))
  
  val out_sign  = Reg(Bool())     
  val saved_op  = Reg(FuOpType())
  val saved_id  = Reg(UInt(4.W))
  val saved_rd  = Reg(UInt(5.W))
  val saved_wen = Reg(Bool())
  val saved_pc  = Reg(UInt(32.W)) 
  val is_special = RegInit(false.B)

  val op = io.in.bits.fuOp
  val is_signed = op === MDUOpType.div || op === MDUOpType.rem
  
  // 符号位提取
  val s1_sign = io.in.bits.src1(31) && is_signed
  val s2_sign = io.in.bits.src2(31) && is_signed
  
  // 绝对值计算 
  val s1_abs = Mux(s1_sign, -io.in.bits.src1, io.in.bits.src1)
  val s2_abs = Mux(s2_sign, -io.in.bits.src2, io.in.bits.src2)
  
  val is_div_by_zero = (io.in.bits.src2 === 0.U)
  val is_overflow    = is_signed && (io.in.bits.src1 === "h80000000".U) && (io.in.bits.src2.asSInt === -1.S)

  switch(state) {
    is(s_idle) {
      io.in.ready := true.B
      
      when(io.in.valid) {
        saved_op  := op
        saved_id  := io.in.bits.uop_id
        saved_rd  := io.in.bits.rdAddr
        saved_wen := io.in.bits.rfWen
        saved_pc  := io.in.bits.pc 
        
        out_sign := Mux(op === MDUOpType.div || op === MDUOpType.divu, s1_sign ^ s2_sign, s1_sign)

        when (is_div_by_zero || is_overflow) {
          state := s_done
          is_special := true.B
          
          val bypass_quotient = Mux(is_div_by_zero, Fill(32, 1.U), io.in.bits.src1)
          val bypass_remainder = Mux(is_div_by_zero, io.in.bits.src1, 0.U)
          // 格式化进 shift_reg 以便统一输出
          shift_reg := Cat(bypass_remainder, bypass_quotient) 
        } .otherwise {
          state := s_div
          is_special := false.B
          count := 32.U
          
          // 初始化逻辑
          // 高 33 位清零，低 32 位放被除数绝对值
          shift_reg := Cat(0.U(33.W), s1_abs) 
          divisor   := Cat(0.U(1.W), s2_abs)
        }
      }
    }
    
    is(s_div) {
      // 标准恢复余数法逻辑
      
      // 1. 整体左移一位 (腾出最低位给新商位，最高位移入余数区)
      val shift_temp = Cat(shift_reg(63, 0), 0.U(1.W))
      
      // 2. 提取当前的高 33 位作为“尝试余数”
      val rem_high = shift_temp(64, 32)
      
      // 3. 试减
      val enough = rem_high >= divisor
      
      // 4. 更新寄存器
      // 如果够减：余数 = 余数 - 除数，最低位(商) = 1
      // 如果不够：余数不变(保持移位后的值)，最低位(商) = 0
      
      // 注意：shift_temp(31, 1) 是移位后的中间段，Cat(..., 1.U) 把最低位补1
      shift_reg := Mux(enough, 
                       Cat(rem_high - divisor, shift_temp(31, 1), 1.U(1.W)), 
                       shift_temp)

      count := count - 1.U
      when(count === 1.U) { state := s_done }
    }
    
    is(s_done) {
      io.out.valid := true.B
      
      // 从寄存器的高低位提取结果
      val raw_remainder = shift_reg(64, 32) // 注意这里是 [64:32] 33位，截取低32位即可
      val raw_quotient  = shift_reg(31, 0)
      
      // 符号修正
      val is_corner_case = is_special
      // 这里的 Mux 逻辑：如果是 corner case，直接用寄存器里的值（因为在 s_idle 已经处理好了）
      // 如果是正常计算，则根据 out_sign 取反
      val res_div = Mux(out_sign && !is_corner_case, -raw_quotient, raw_quotient)
      // 注意：余数是 33 位截断成 32 位
      val res_rem = Mux(out_sign && !is_corner_case, -raw_remainder(31, 0), raw_remainder(31, 0))
      
      val is_div_op = saved_op === MDUOpType.div || saved_op === MDUOpType.divu
      
      io.out.bits.data := Mux(is_div_op, res_div, res_rem)
      io.out.bits.uop_id := saved_id
      io.out.bits.rdAddr := saved_rd
      io.out.bits.rfWen  := saved_wen
      io.out.bits.pc     := saved_pc

      when(io.out.ready) { state := s_idle }
    }
  }

  io.pending.busy := io.in.valid || (state =/= s_idle && !io.out.valid)
  io.pending.rd   := Mux(state === s_idle, io.in.bits.rdAddr, saved_rd)
  io.pending.id   := Mux(state === s_idle, io.in.bits.uop_id, saved_id)
}