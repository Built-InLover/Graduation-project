package common

import chisel3._
import chisel3.util._

// 请求通道：Master -> Slave
class SimpleBusReq extends Bundle {
  val addr  = UInt(32.W)
  val wen   = Bool()
  val wdata = UInt(32.W)
  val wmask = UInt(4.W)
}

// 响应通道：Slave -> Master
class SimpleBusResp extends Bundle {
  val rdata = UInt(32.W)
}

// 完整的 SimpleBus
class SimpleBus extends Bundle {
  val req  = Decoupled(new SimpleBusReq)
  val resp = Flipped(Decoupled(new SimpleBusResp))
}