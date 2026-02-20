package common

import chisel3._
import chisel3.util._

// ==========================================
// AXI4 参数配置类
// ==========================================
case class AXI4Params(
  addrWidth: Int = 32,
  dataWidth: Int = 32,
  idWidth: Int = 4
)

// ==========================================
// 1. 定义各个通道的 Bundle
// ==========================================

// --- 写地址通道 (Write Address Channel) ---
class AXI4AW(params: AXI4Params) extends Bundle {
  val addr  = UInt(params.addrWidth.W)
  val id    = UInt(params.idWidth.W)
  val len   = UInt(8.W)  // Burst length - 1 (0 = 1 transfer)
  val size  = UInt(3.W)  // Bytes per beat: 2^size
  val burst = UInt(2.W)  // 00=FIXED, 01=INCR, 10=WRAP
}

// --- 写数据通道 (Write Data Channel) ---
class AXI4W(params: AXI4Params) extends Bundle {
  val data = UInt(params.dataWidth.W)
  val strb = UInt((params.dataWidth / 8).W)
  val last = Bool()  // Last transfer in burst
}

// --- 写响应通道 (Write Response Channel) ---
class AXI4B(params: AXI4Params) extends Bundle {
  val id   = UInt(params.idWidth.W)
  val resp = UInt(2.W)  // 00=OKAY, 01=EXOKAY, 10=SLVERR, 11=DECERR
}

// --- 读地址通道 (Read Address Channel) ---
class AXI4AR(params: AXI4Params) extends Bundle {
  val addr  = UInt(params.addrWidth.W)
  val id    = UInt(params.idWidth.W)
  val len   = UInt(8.W)
  val size  = UInt(3.W)
  val burst = UInt(2.W)
}

// --- 读数据通道 (Read Data Channel) ---
class AXI4R(params: AXI4Params) extends Bundle {
  val id   = UInt(params.idWidth.W)
  val data = UInt(params.dataWidth.W)
  val resp = UInt(2.W)
  val last = Bool()
}

// ==========================================
// 2. 定义总线接口 Bundle
// ==========================================
class AXI4Interface(params: AXI4Params) extends Bundle {
  // 写通道群
  val aw = Decoupled(new AXI4AW(params))
  val w  = Decoupled(new AXI4W(params))
  val b  = Flipped(Decoupled(new AXI4B(params)))

  // 读通道群
  val ar = Decoupled(new AXI4AR(params))
  val r  = Flipped(Decoupled(new AXI4R(params)))
}
