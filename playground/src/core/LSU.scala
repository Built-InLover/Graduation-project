package core
import chisel3._
import chisel3.util._

object LSUOpType {
  def lb   = "b0000000".U
  def lh   = "b0000001".U
  def lw   = "b0000010".U
  def lbu  = "b0000100".U
  def lhu  = "b0000101".U
  def sb   = "b0001000".U
  def sh   = "b0001001".U
  def sw   = "b0001010".U

  def isStore(func: UInt): Bool = func(3)
  def isLoad(func: UInt): Bool = !isStore(func)  
  def isSigned(func: UInt): Bool = !func(2)
  def mask(func: UInt): UInt = MuxLookup(func(1, 0), "b0000".U)(Seq(
  "b00".U -> "b0001".U,  // Byte (b)
  "b01".U -> "b0011".U,  // Halfword (h)
  "b10".U -> "b1111".U   // Word (w)
))
}
//读的时候，直接读32位，所以rdata需要根据mask进行掩码操作写的时候，不能影响不打算写的位置，所以写的时候就需要对掩码进行操作，在这里抽象了一个Memory作为黑盒，它的行为就是，直接读32写时做掩码，后期加入soc之后，就需要把写的掩码操作也使用chisel来写而不是依赖dpic
class LSU extends Module {
  val io = IO(new Bundle {
    val addr   = Input(UInt(32.W))  // 计算得到的地址
    val dataIn = Input(UInt(32.W))  // 要存入内存的数据（Store）
    val mask   = Input(UInt(4.W))   // 字节使能（Store 时使用）
    val wen    = Input(Bool())      // 写使能
    val ren    = Input(Bool())      //
	val sign   = Input(Bool())
    val rdata  = Output(UInt(32.W)) // 读取的数据（Load）
    val mem    = new MemoryIO // 连接外部 Memory
  })

  // 计算地址对齐情况
  val alignedAddr = io.addr(1, 0)

  // 读取数据
  val rawData = io.mem.readData

io.rdata := Mux(io.ren, MuxLookup(io.mask, 0.U(32.W))(Seq(
  // LB / LBU
  "b0001".U -> Mux(io.sign,
    // 有符号扩展（统一返回UInt类型）
    MuxLookup(io.addr(1,0), 0.U(32.W))(Seq(
      0.U -> Cat(Fill(24, rawData(7)), rawData(7, 0)).asUInt,
      1.U -> Cat(Fill(24, rawData(15)), rawData(15, 8)).asUInt,
      2.U -> Cat(Fill(24, rawData(23)), rawData(23, 16)).asUInt,
      3.U -> Cat(Fill(24, rawData(31)), rawData(31, 24)).asUInt
    )),
    // 零扩展（保持UInt类型）
    MuxLookup(io.addr(1,0), 0.U(32.W))(Seq(
      0.U -> Cat(Fill(24, 0.U), rawData(7, 0)),
      1.U -> Cat(Fill(24, 0.U), rawData(15, 8)),
      2.U -> Cat(Fill(24, 0.U), rawData(23, 16)),
      3.U -> Cat(Fill(24, 0.U), rawData(31, 24))
    ))
  ),

  // LH / LHU
  "b0011".U -> Mux(io.sign,
    // 有符号扩展（半字）
    Mux(io.addr(1),
      Cat(Fill(16, rawData(31)), rawData(31, 16)).asUInt,  // 高半字
      Cat(Fill(16, rawData(15)), rawData(15, 0)).asUInt   // 低半字
    ),
    // 零扩展（半字）
    Mux(io.addr(1),
      Cat(Fill(16, 0.U), rawData(31, 16)),
      Cat(Fill(16, 0.U), rawData(15, 0))
    )
  ),

  // LW
  "b1111".U -> rawData  // 直接返回UInt
)), 0.U)

  io.mem.writeEnable := io.wen
  io.mem.writeMask   := Mux(io.wen, io.mask, 0.U)
  io.mem.readEnable	:= io.ren
  io.mem.addr := io.addr
  io.mem.writeData   := Mux(io.wen, io.dataIn, 0.U)
}
//不需要处理写，因为目前写的细节都是用DPIC实现的，这里只要把数据直接传送过去就可以

// 定义一个简单的 Memory IO 接口
class MemoryIO extends Bundle {
  val readData    = Input(UInt(32.W)) // 读取的数据
  val readEnable  = Output(Bool())
  val addr        = Output(UInt(32.W))
  val writeEnable = Output(Bool())   // 写使能
  val writeMask   = Output(UInt(4.W)) // 写入字节掩码
  val writeData   = Output(UInt(32.W)) // 写入数据
}
