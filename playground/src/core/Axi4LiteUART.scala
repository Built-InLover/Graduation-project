package core

import chisel3._
import chisel3.util._
import common._

// --- 1. DPI-C 黑盒包装 ---
class UARTBlackBox extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val valid = Input(Bool())
    val ch    = Input(UInt(8.W))
  })

  setInline("UARTBlackBox.sv",
    """module UARTBlackBox(
      |  input clock,
      |  input valid,
      |  input [7:0] ch
      |);
      |  import "DPI-C" function void uart_putchar(input byte unsigned c);
      |
      |  always @(posedge clock) begin
      |    if (valid) begin
      |      uart_putchar(ch);
      |    end
      |  end
      |endmodule
    """.stripMargin)
}

// --- 2. AXI4-Lite UART 逻辑 ---
class AXI4LiteUART extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new AXI4LiteInterface(AXI4LiteParams(32, 32)))
  })

  // 读通道 (丢弃读请求，返回 0)
  io.bus.ar.ready := true.B
  io.bus.r.valid  := RegNext(io.bus.ar.fire, false.B)
  io.bus.r.bits.data := 0.U
  io.bus.r.bits.resp := "b00".U 

  // 写通道握手
  val b_valid = RegInit(false.B)
  val can_write = io.bus.aw.valid && io.bus.w.valid && !b_valid
  io.bus.aw.ready := can_write
  io.bus.w.ready  := can_write

  when(can_write) {
    b_valid := true.B
  }.elsewhen(io.bus.b.fire) {
    b_valid := false.B
  }
  io.bus.b.valid := b_valid
  io.bus.b.bits.resp := "b00".U

  // 实例化黑盒并连线
  val bb = Module(new UARTBlackBox())
  bb.io.clock := clock
  bb.io.valid := io.bus.aw.fire && io.bus.w.fire
  bb.io.ch    := io.bus.w.bits.data(7, 0) // 提取低 8 位作为字符
}