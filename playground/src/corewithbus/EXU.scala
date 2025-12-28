package corewithbus

import chisel3._
import chisel3.util._
import essentials._
class EXU extends Module with HasInstrType {
  val io = IO(new Bundle {
    // 1. 输入：来自 IDU (已经封装好的任务)
    val in = Flipped(Decoupled(new Bundle {
      val pc       = UInt(32.W)
      val src1     = UInt(32.W)
      val src2     = UInt(32.W)
      val imm      = UInt(32.W)
      val fuType   = FuType()     // 3位：ALU, LSU, etc.
      val fuOp     = FuOpType()   // 7位：精确操作码
      val rfWen    = Bool()
      val rdAddr   = UInt(5.W)
      val isLoad   = Bool()
      val isStore  = Bool()
      val isBranch = Bool()
      val isJump   = Bool()
    }))

    // 2. 输出 A：给 LSU (访存指令)
    val lsuOut = Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val addr   = UInt(32.W)
      val wdata  = UInt(32.W)
      val func   = FuOpType()     // 直接传 fuOp 过去，LSU 内部有语义函数
      val rdAddr = UInt(5.W)
    })

    // 3. 输出 B：给 WBU (写回单元)
    val wbuOut = Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val data   = UInt(32.W)
      val rdAddr = UInt(5.W)
      val rfWen  = Bool()
      val is_csr = Bool()
    })

    // 4. 侧面输出：给 IFU (跳转重定向)
    val redirect = Valid(new Bundle {
      val targetPC = UInt(32.W)
    })
  })

  io.lsuOut.bits.pc := io.in.bits.pc
  io.wbuOut.bits.pc := io.in.bits.pc
  // --- [1] ALU 核心逻辑 ---
  // 利用语义化编码：Bit(3) 是减法/算术右移标志，Bit(2:0) 是功能索引
  val aluOp  = io.in.bits.fuOp
  val src1   = io.in.bits.src1
  val src2   = io.in.bits.src2
  
  val shamt  = src2(4, 0) // 移位量固定取 src2 的低 5 位
  
  val aluResult = MuxLookup(aluOp(2, 0), 0.U)(Seq(
    0.U -> Mux(ALUOpType.isSub(aluOp), src1 - src2, src1 + src2), // ADD/SUB
    1.U -> (src1 << shamt),                                       // SLL
    2.U -> (src1.asSInt < src2.asSInt).asUInt,                    // SLT
    3.U -> (src1 < src2).asUInt,                                  // SLTU
    4.U -> (src1 ^ src2),                                         // XOR
    5.U -> Mux(ALUOpType.isSra(aluOp), (src1.asSInt >> shamt).asUInt, src1 >> shamt), // SRL/SRA
    6.U -> (src1 | src2),                                         // OR
    7.U -> (src1 & src2)                                          // AND
  ))

  // --- [2] BRU 核心逻辑 (分支比较) ---
  // 同样利用 fuOp 的低 3 位 (直接对应 func3)
  val compRes = MuxLookup(aluOp(2, 0), false.B)(Seq(
    0.U -> (src1 === src2),                  // BEQ
    1.U -> (src1 =/= src2),                  // BNE
    4.U -> (src1.asSInt < src2.asSInt),      // BLT
    5.U -> (src1.asSInt >= src2.asSInt),     // BGE
    6.U -> (src1 < src2),                    // BLTU
    7.U -> (src1 >= src2)                    // BGEU
  ))

  val branchTake = (io.in.bits.isBranch && compRes) || io.in.bits.isJump

  // 跳转目标计算
  // JALR 指令比较特殊：它是 rs1 + imm，且最后一位清零
  // 其余指令都是 PC + imm
  val basePC = Mux(aluOp === BRUOpType.jalr, src1, io.in.bits.pc)
  val targetPC = (basePC + io.in.bits.imm) & (~1.U(32.W))
//--仿真需要--
  io.lsuOut.bits.dnpc := Mux(io.redirect.valid, targetPC, io.lsuOut.bits.pc + 4.U)
  io.wbuOut.bits.dnpc := Mux(io.redirect.valid, targetPC, io.wbuOut.bits.pc + 4.U)
  io.wbuOut.bits.is_csr := Mux(io.in.bits.fuType === FuType.csr, true.B, false.B)
//------------
  io.redirect.valid         := io.in.valid && branchTake
  io.redirect.bits.targetPC := targetPC

  // --- [3] 结果分发 (Dispatch) ---
  val isMemOp = io.in.bits.fuType === FuType.lsu

  // 情况 A: 发给 LSU (只有 Load/Store 指令去这里)
  io.lsuOut.valid       := io.in.valid && isMemOp
  io.lsuOut.bits.addr   := aluResult       // 已经计算好的基址 + 偏移
  io.lsuOut.bits.wdata  := io.in.bits.src2 // Store 的数据
  io.lsuOut.bits.func   := aluOp           // 传 7 位编码，LSU 内部用 isStore/mask 等函数
  io.lsuOut.bits.rdAddr := io.in.bits.rdAddr

  // 情况 B: 发给 WBU
  // 注意：Jump 指令写回的是 PC + 4，其余写回 ALU 结果
  val wbData = Mux(io.in.bits.isJump, io.in.bits.pc + 4.U, aluResult)
  
  io.wbuOut.valid       := io.in.valid && !isMemOp && !io.in.bits.isBranch
  io.wbuOut.bits.data   := wbData
  io.wbuOut.bits.rdAddr := io.in.bits.rdAddr
  io.wbuOut.bits.rfWen  := io.in.bits.rfWen

  // --- [4] 输入握手 ---
  val downstreamReady = Mux(isMemOp, io.lsuOut.ready, io.wbuOut.ready)
  io.in.ready := downstreamReady || io.redirect.valid
}