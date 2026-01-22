package corewithbus

import chisel3._
import chisel3.util._
import essentials._
/*
exu的alu的src来源要处理一下
仿真环境里one_cycle的电平反转一下，然后出while循环的时候让仿真环境主动去读，而不是原来的寄存器写入和寄存器数据发给仿真环境同时进行
*/
class EXU extends Module with HasInstrType {
  val io = IO(new Bundle {
    // 1. 输入：来自 IDU
    val in = Flipped(Decoupled(new Bundle {
      val pc       = UInt(32.W)
      val src1     = UInt(32.W)
      val src2     = UInt(32.W)
      val imm      = UInt(32.W)
      val fuType   = FuType()     
      val fuOp     = FuOpType()   
      val rfWen    = Bool()
      val rdAddr   = UInt(5.W)
      val isLoad   = Bool()
      val isStore  = Bool()
      val isBranch = Bool()
      val isJump   = Bool()
      val useImm   = Bool()
    }))

    // 2. 输出 A：给 LSU
    val lsuOut = Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val addr   = UInt(32.W)
      val wdata  = UInt(32.W)
      val func   = FuOpType()     
      val rdAddr = UInt(5.W)
    })

    // 3. 输出 B：给 WBU
    val wbuOut = Decoupled(new Bundle {
      val pc     = UInt(32.W)
      val dnpc   = UInt(32.W)
      val data   = UInt(32.W)
      val rdAddr = UInt(5.W)
      val rfWen  = Bool()
      val is_csr = Bool()
    })

    // 4. 重定向
    val redirect = Valid(new Bundle {
      val targetPC = UInt(32.W)
    })
  })

  // 基础连接
  io.lsuOut.bits.pc := io.in.bits.pc
  io.wbuOut.bits.pc := io.in.bits.pc

  // 选择 ALU 操作数 2 ---
  // 对于访存指令 (LSU) 和 立即数类运算 (ALU_IMM)，操作数 2 应该是立即数
  val isMemOp  = io.in.bits.fuType === FuType.lsu

  
  // --- 移位量处理 ---
  // 移位指令如果是 SLLI/SRLI/SRAI，移位量在立即数里；如果是 SLL/SRL/SRA，在 src2 里
  val src1     = io.in.bits.src1
  val aluIn2 = Mux(io.in.bits.useImm, io.in.bits.imm, io.in.bits.src2)
  val shamt    = aluIn2(4, 0) 
  val aluOp    = io.in.bits.fuOp
  
  // --- 强化加减法逻辑 ---
  val aluResult = Mux(isMemOp, 
    // 1. 如果是访存，永远执行加法计算地址
    src1 + aluIn2, 
    // 2. 否则，根据 funct3 执行标准运算
    MuxLookup(aluOp(2, 0), 0.U)(Seq(
      0.U -> Mux(ALUOpType.isSub(aluOp), src1 - aluIn2, src1 + aluIn2), 
      1.U -> (src1 << shamt),
      2.U -> (src1.asSInt < aluIn2.asSInt).asUInt,
      3.U -> (src1 < aluIn2).asUInt,
      4.U -> (src1 ^ aluIn2),
      5.U -> Mux(ALUOpType.isSra(aluOp), (src1.asSInt >> shamt).asUInt, src1 >> shamt),
      6.U -> (src1 | aluIn2),
      7.U -> (src1 & aluIn2)
    ))
  )

  // --- [2] BRU 核心逻辑 ---
  // 分支比较始终使用原始寄存器值 (src1 vs src2)
  val compRes = MuxLookup(aluOp(2, 0), false.B)(Seq(
    0.U -> (src1 === io.in.bits.src2),           // BEQ
    1.U -> (src1 =/= io.in.bits.src2),           // BNE
    4.U -> (src1.asSInt < io.in.bits.src2.asSInt), // BLT
    5.U -> (src1.asSInt >= io.in.bits.src2.asSInt),// BGE
    6.U -> (src1 < io.in.bits.src2),             // BLTU
    7.U -> (src1 >= io.in.bits.src2)             // BGEU
  ))

  val branchTake = (io.in.bits.isBranch && compRes) || io.in.bits.isJump

  // 跳转目标计算
  val basePC   = Mux(aluOp === BRUOpType.jalr, src1, io.in.bits.pc)
  val targetPC = (basePC + io.in.bits.imm) & (~1.U(32.W))
  // 仿真与写回逻辑
  val dnpc = Mux(branchTake, targetPC, io.in.bits.pc + 4.U)
  io.lsuOut.bits.dnpc := dnpc
  io.wbuOut.bits.dnpc := dnpc
  io.wbuOut.bits.is_csr := io.in.bits.fuType === FuType.csr

  val wbu_fire = io.wbuOut.fire 
  // 只有握手成功那一拍，才允许发出跳转信号，跳转信号一定不是访存指令，所以走wbu退休
  io.redirect.valid     := wbu_fire && branchTake
  io.redirect.bits.targetPC := targetPC


  // --- [3] 结果分发 (Dispatch) ---
  // LSU
  io.lsuOut.valid       := io.in.valid && isMemOp
  io.lsuOut.bits.addr    := aluResult
  io.lsuOut.bits.wdata   := io.in.bits.src2
  io.lsuOut.bits.func    := aluOp
  io.lsuOut.bits.rdAddr  := io.in.bits.rdAddr

  // WBU
  val wbData = Mux(io.in.bits.isJump, io.in.bits.pc + 4.U, aluResult)
  io.wbuOut.valid         := io.in.valid && !isMemOp
  io.wbuOut.bits.data    := wbData
  io.wbuOut.bits.rdAddr  := io.in.bits.rdAddr
  io.wbuOut.bits.rfWen   := Mux(io.in.bits.isBranch, false.B, io.in.bits.rfWen)

  // --- [4] 输入握手 ---
  // --- [输入握手逻辑微调] ---
  val downstreamReady = Mux(isMemOp, io.lsuOut.ready, io.wbuOut.ready)
  
  // 之前的写法：
  // io.in.ready := downstreamReady || io.redirect.valid
  // 现在的写法：
  // 因为 redirect 绑定了 fire (即依赖 downstreamReady)，所以这里逻辑其实没变，
  // 只是含义变成了：如果下游肯收，我就 Ready；收了之后顺便把 Redirect 发出去。
  io.in.ready := downstreamReady
}