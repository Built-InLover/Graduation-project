package core

import chisel3._
import chisel3.util._
import essentials._

// DPI-C BlackBox for sim_ebreak
class SimEbreak extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val trigger = Input(Bool())
  })
  setInline("SimEbreak.v",
    """module SimEbreak(
      |  input trigger
      |);
      |  import "DPI-C" function void sim_ebreak();
      |  always @(*) begin
      |    if (trigger) sim_ebreak();
      |  end
      |endmodule
      |""".stripMargin)
}

// ==================================================================
// CSRUnit
// ==================================================================
class CSRUnit extends Module {
  val io = IO(new Bundle {
    // --- 流水线输入 (EXU Stage) ---
    val in = Flipped(Decoupled(new Bundle {
      val src1   = UInt(32.W)
      val imm    = UInt(32.W)
      val func   = FuOpType()
      val uop_id = UInt(4.W)
      val pc     = UInt(32.W)
    }))

    // --- 流水线写回输出 (WBU Stage) ---
    val out = Decoupled(new Bundle {
      val data   = UInt(32.W)       // 对应 rdata
      val uop_id = UInt(4.W)
      // 注意：CSR 写操作也会写回 rd 寄存器(旧值)，除了 Trap 类指令
    })
    // --- 重定向输出 (给 IFU/IDU) ---
    // 对应原来的 dnpc 和 trap 信号
    val redirect = Valid(new Bundle {
      val targetPC = UInt(32.W)
      val is_privileged   = Bool()         // 标记这是 CSR 引起的跳转
    })

    val debug_csr   = Output(Vec(4, UInt(32.W)))
  })

  // ==================================================================
  // 1. 状态寄存器 (Copy from Reference)
  // ==================================================================
  val reg_mstatus = RegInit(0.U(32.W)) // MPP=3 (Machine Mode)
  val reg_mepc    = RegInit(0.U(32.W))
  val reg_mcause  = RegInit(0.U(32.W))
  val reg_mtvec   = RegInit(0.U(32.W))

  io.debug_csr(0) := reg_mcause
  io.debug_csr(1) := reg_mepc
  io.debug_csr(2) := reg_mstatus
  io.debug_csr(3) := reg_mtvec

  // ==================================================================
  // 2. 信号解析与映射
  // ==================================================================
  // 从流水线包中解压信号，映射回你熟悉的变量名
  val csrOpType = io.in.bits.func
  val csrAddr   = io.in.bits.imm(11, 0) // 取低12位作为地址
  val csrRs1    = io.in.bits.src1
  val currentPC = io.in.bits.pc

  // 判断是否是特权指令 (ECALL/MRET)
  // 假设 IDU 传进来的 imm 对于 ECALL 为 0，对于 MRET 为 0x302 (或其他非0值区分)
  // 你也可以根据具体的 imm 编码来改写这里
  val is_jmp   = io.in.valid && csrOpType === CSROpType.jmp

  val is_ebreak = is_jmp && io.in.bits.imm === 1.U      
  val is_ecall = is_jmp && io.in.bits.imm === 0.U      // 假设 IDU 传 0
  val is_mret  = is_jmp && io.in.bits.imm === 0x302.U  // 假设 IDU 传 0x302 (MRET funct12)

  val is_trap   = is_ecall || is_ebreak
  
  // 当前特权级 (暂时写死 Machine Mode，后续可扩展)
  val currentPriv = PrivilegeLevel.PRV_M

  // ==================================================================
  // 3. 读逻辑 (Read Logic) - 保持不变
  // ==================================================================
  val csrReadData = WireInit(0.U(32.W))
  switch(csrAddr) {
    is(CSRAddress.MSTATUS) { csrReadData := reg_mstatus }
    is(CSRAddress.MEPC)    { csrReadData := reg_mepc }
    is(CSRAddress.MCAUSE)  { csrReadData := reg_mcause }
    is(CSRAddress.MTVEC)   { csrReadData := reg_mtvec }
  }

  // ==================================================================
  // 4. 写与操作逻辑 (Write & Priv Logic) - 核心逻辑移植
  // ==================================================================
  
  // 计算新值 (用于 Wrt/Set/Clr)
  val csrNewValue = WireInit(0.U(32.W))
  switch(csrOpType) {
    is(CSROpType.wrt) { csrNewValue := csrRs1 }                // csrrw
    is(CSROpType.set) { csrNewValue := csrReadData | csrRs1 }  // csrrs
    is(CSROpType.clr) { csrNewValue := csrReadData & ~csrRs1 } // csrrc
  }

  // 执行更新
  when(io.in.valid) {
    when(is_trap) { 
      // 1. 保存 PC 到 MEPC
      // 注意：无论是 ecall 还是 ebreak，mepc 都指向由于该指令触发异常的那条指令本身
      reg_mepc := io.in.bits.pc

      // 2. 更新 MCAUSE
      when(is_ebreak) {
        reg_mcause := CauseCode.BREAKPOINT // [新增] 固定为 3
      } .otherwise {
        // ECALL 根据当前特权级区分
        reg_mcause := MuxCase(CauseCode.ENVCALL_M, Seq(
          (currentPriv === PrivilegeLevel.PRV_U) -> CauseCode.ENVCALL_U,
          (currentPriv === PrivilegeLevel.PRV_S) -> CauseCode.ENVCALL_S
        ))
      }

      // 3. 更新 MSTATUS (保存中断上下文)
      // 逻辑与 ECALL 完全一致：关中断，保存旧中断状态，设置 MPP
      val mpie = (reg_mstatus & 0x8.U) << 4 
      reg_mstatus := (reg_mstatus & ~0x1888.U) | mpie | 0x1800.U
    }
    .elsewhen(is_mret) {
      // --- MRET Logic (Copy from Ref) ---
      // Move MPIE(bit 7) to MIE(bit 3)
      val mretNewMstatus = (reg_mstatus | ((reg_mstatus & 0x80.U) >> 4)) | 0x80.U // MPIE set to 1
      reg_mstatus := mretNewMstatus & ~0x1800.U // Clear MPP ? (注意：这里根据你的逻辑，MPP被清零了，意味着回到了U模式)
    }
    .elsewhen(csrOpType === CSROpType.wrt || csrOpType === CSROpType.set || csrOpType === CSROpType.clr) {
      // --- CSR RW Logic ---
      switch(csrAddr) {
        is(CSRAddress.MSTATUS) { reg_mstatus := csrNewValue }
        is(CSRAddress.MEPC)    { reg_mepc    := csrNewValue }
        is(CSRAddress.MCAUSE)  { reg_mcause  := csrNewValue }
        is(CSRAddress.MTVEC)   { reg_mtvec   := csrNewValue }
      }
    }
  }

  // ==================================================================
  // 5. 输出逻辑
  // ==================================================================
  
  // 握手直接透传
  io.in.ready := io.out.ready

  // [修改] 之前是 io.out.valid := io.in.valid && !is_jmp
  // 现在：只要有输入，就允许输出流向 WBU。
  // 即使是 Trap/Mret，也要流下去，哪怕不写寄存器。
  io.out.valid       := io.in.valid 
  
  io.out.bits.data   := csrReadData
  io.out.bits.uop_id := io.in.bits.uop_id

  // 重定向逻辑保持不变 (依然要在 EXU 产生跳转信号)
  io.redirect.valid    := is_jmp
  io.redirect.bits.is_privileged   := true.B
  io.redirect.bits.targetPC := Mux(is_trap, reg_mtvec, reg_mepc)

  // DPI-C: notify testbench on ebreak
  val sim_ebreak_inst = Module(new SimEbreak)
  sim_ebreak_inst.io.trigger := is_ebreak && io.in.valid
}