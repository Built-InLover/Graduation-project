package common

import chisel3._
import chisel3.util._

// ==========================================
// AXI4-Lite 参数配置类 (可选，方便后续扩展)
// ==========================================
case class AXI4LiteParams(
  addrWidth: Int = 32,
  dataWidth: Int = 32
)

// ==========================================
// 1. 定义各个通道的 Bundle
// ==========================================

// --- 写地址通道 (Write Address Channel) ---
class AXI4LiteAW(params: AXI4LiteParams) extends Bundle {
  val addr = UInt(params.addrWidth.W)
  val prot = UInt(3.W) // 保护类型 (通常设为 "b000".U 即可)
}

// --- 写数据通道 (Write Data Channel) ---
class AXI4LiteW(params: AXI4LiteParams) extends Bundle {
  val data = UInt(params.dataWidth.W)
  val strb = UInt((params.dataWidth / 8).W) // 写掩码 (Strobe), 1 bit 对应 1 byte
}

// --- 写响应通道 (Write Response Channel) ---
class AXI4LiteB extends Bundle {
  val resp = UInt(2.W) // "b00" = OKAY, "b01" = EXOKAY, "b10" = SLVERR, "b11" = DECERR
}

// --- 读地址通道 (Read Address Channel) ---
class AXI4LiteAR(params: AXI4LiteParams) extends Bundle {
  val addr = UInt(params.addrWidth.W)
  val prot = UInt(3.W)
}

// --- 读数据通道 (Read Data Channel) ---
class AXI4LiteR(params: AXI4LiteParams) extends Bundle {
  val data = UInt(params.dataWidth.W)
  val resp = UInt(2.W) // 读操作的状态
}

// ==========================================
// 2. 定义总线接口 Bundle
// ==========================================
class AXI4LiteInterface(params: AXI4LiteParams) extends Bundle {
  // 注意：Decoupled 自动添加 valid 和 ready
  
  // 写通道群
  val aw = Decoupled(new AXI4LiteAW(params)) // Write Address
  val w  = Decoupled(new AXI4LiteW(params))  // Write Data
  val b  = Flipped(Decoupled(new AXI4LiteB)) // Write Response (方向是 Slave -> Master)

  // 读通道群
  val ar = Decoupled(new AXI4LiteAR(params)) // Read Address
  val r  = Flipped(Decoupled(new AXI4LiteR(params))) // Read Data (方向是 Slave -> Master)
  
  // 辅助函数：连接两个 AXI 接口 (Master <> Slave)
  // 如果你在 CPU 顶层连线，可以直接用 <>，这个函数备用
  def connect(slave: AXI4LiteInterface): Unit = {
    slave.aw <> this.aw
    slave.w  <> this.w
    this.b   <> slave.b
    slave.ar <> this.ar
    this.r   <> slave.r
  }
}
