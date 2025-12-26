package core

import chisel3._
import chisel3.util._

// object CSROpType {    
//   def jmp  = "b000".U  
//   def wrt  = "b001".U  
//   def set  = "b010".U  
//   def clr  = "b011".U  
// }

// // CSR 地址常量定义
// object CSRAddress {
//   val MSTATUS = 0x300.U(12.W)  
//   val MEPC    = 0x341.U(12.W)  
//   val MCAUSE  = 0x342.U(12.W)  
//   val MTVEC   = 0x305.U(12.W) 
// }

// // 异常原因编码
// object CauseCode {
//   val ENVCALL_U = 8.U(32.W)  
//   val ENVCALL_S = 9.U(32.W)  
//   val ENVCALL_M = 11.U(32.W) 
// }

// // 特权级编码
// object PrivilegeLevel {
//   val PRV_U = 0.U(2.W)
//   val PRV_S = 1.U(2.W)
//   val PRV_M = 3.U(2.W)
// }

// class CSRU extends Module {
//   val io = IO(new Bundle {
//     val csrOpType = Input(UInt(3.W))
    
//     // CSR 指令信号
//     val csrCmd = Input(new Bundle {
//       val valid    = Bool()
//       val addr     = UInt(12.W)
//       val rs1      = UInt(32.W)
//     })
    
//     // 特权指令信号
//     val privOp = Input(new Bundle {
//       val mret     = Bool()
//       val ecall    = Bool()
//       val pc       = UInt(32.W)
//     })
    
//     // 输出信号
//     val rdata     = Output(UInt(32.W))
//     val dnpc      = Output(UInt(32.W))
//     val trap      = Output(Bool())
//   })

//   // CSR 寄存器状态定义
//   class CSRState extends Bundle {
//     val mstatus = UInt(32.W)   // 0x300
//     val mepc    = UInt(32.W)   // 0x341
//     val mcause  = UInt(32.W)   // 0x342
//     val mtvec   = UInt(32.W)   // 0x305
//   }

//   val csrState = RegInit({
//     val init = Wire(new CSRState)
//     init.mstatus := 0x1800.U    // MPP=3 (Machine Mode), 其他位默认0
//     init.mepc    := 0.U
//     init.mcause  := 0.U
//     init.mtvec   := 0.U
//     init
//   })

//   // CSR 读数据
//   val csrReadData = WireInit(0.U(32.W))
//   switch(io.csrCmd.addr) {
//     is(CSRAddress.MSTATUS) { csrReadData := csrState.mstatus }
//     is(CSRAddress.MEPC)    { csrReadData := csrState.mepc }
//     is(CSRAddress.MCAUSE)  { csrReadData := csrState.mcause }
//     is(CSRAddress.MTVEC)   { csrReadData := csrState.mtvec }
//   }

//   // CSR 写数据
//   val csrNewValue = WireInit(0.U(32.W))

//   // 当前特权级（假设为机器模式）
//   val currentPriv = PrivilegeLevel.PRV_M

//   // 操作处理
//   when(io.csrOpType === CSROpType.jmp) {
//     // jmp 操作：处理 ecall 和 mret
//     when(io.privOp.mret) {
//       // mret
//       val mretNewMstatus = (csrState.mstatus | ((csrState.mstatus & 0x80.U) >> 4)) | 0x80.U
//       csrState.mstatus := mretNewMstatus & ~0x1800.U
//     }
//     when(io.privOp.ecall) {
//       // ecall
//       val mpie = (csrState.mstatus & 0x8.U) << 4
//       csrState.mstatus := (csrState.mstatus & ~0x1888.U) | mpie | 0x1800.U
//       csrState.mepc := io.privOp.pc
//       csrState.mcause := MuxCase(CauseCode.ENVCALL_M, Seq(
//         (currentPriv === PrivilegeLevel.PRV_U) -> CauseCode.ENVCALL_U,
//         (currentPriv === PrivilegeLevel.PRV_S) -> CauseCode.ENVCALL_S
//       ))
//     }
//   } .elsewhen(io.csrCmd.valid && (io.csrOpType === CSROpType.wrt || io.csrOpType === CSROpType.set || io.csrOpType === CSROpType.clr)) {
//     // wrt, set, clr 操作
//     switch(io.csrOpType) {
//       is(CSROpType.wrt) { csrNewValue := io.csrCmd.rs1 }                // csrrw
//       is(CSROpType.set) { csrNewValue := csrReadData | io.csrCmd.rs1 }  // csrrs
//       is(CSROpType.clr) { csrNewValue := csrReadData & ~io.csrCmd.rs1 } // csrrc
//     }
//     // 写入 CSR 寄存器
//     switch(io.csrCmd.addr) {
//       is(CSRAddress.MSTATUS) { csrState.mstatus := csrNewValue }
//       is(CSRAddress.MEPC)    { csrState.mepc    := csrNewValue }
//       is(CSRAddress.MCAUSE)  { csrState.mcause  := csrNewValue }
//       is(CSRAddress.MTVEC)   { csrState.mtvec   := csrNewValue }
//     }
//   }

//   io.rdata := csrReadData  // 对于普通 CSR 指令，返回读取数据
//   io.dnpc := Mux(io.privOp.ecall, csrState.mtvec, csrState.mepc)
//   io.trap  := io.privOp.ecall && (io.csrOpType === CSROpType.jmp)  // ecall 触发 trap
// }

