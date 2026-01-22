package common

import chisel3._
import chisel3.util._

// 1. 定义数据结构（必须继承 Bundle 才能在 IO 中使用）
class ForwardingBus extends Bundle {
  val valid  = Bool()
  val rdAddr = UInt(5.W)
  val data   = UInt(32.W)
}

// 2. 定义工具逻辑（单例对象）
object ForwardingUnit {
  /**
   * @param rs_addr      IDU想要读的寄存器编号
   * @param default_data 从 RegFile 读出的原始数据
   * @param sources      从外部（Top）传进来的旁路信号集合 (Vec 或 Seq)
   */
  def apply(rs_addr: UInt, default_data: UInt, sources: Seq[ForwardingBus]): UInt = {
    // 使用 map 遍历传入的 sources，生成命中逻辑
    val hits = sources.map { s =>
      s.valid && (s.rdAddr === rs_addr) && (rs_addr =/= 0.U)
    }

    // 组装 MuxCase。hits 和 sources 是一一对应的。
    // 用 zip 把布尔信号和数据对齐
    val muxPairs = hits.zip(sources.map(_.data)).map { case (hit, data) =>
      hit -> data
    }

    MuxCase(default_data, muxPairs)
  }
}