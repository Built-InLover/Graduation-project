package core

import chisel3._
import chisel3.util._
import common._

class SoCTop extends Module {
  val io = IO(new Bundle {
    val ifu_bus = Flipped(new AXI4LiteInterface(AXI4LiteParams(32, 32)))
    val lsu_bus = Flipped(new AXI4LiteInterface(AXI4LiteParams(32, 32)))
  })

  // 实例化所有子设备
  val ram   = Module(new MemSystem())
  val uart  = Module(new AXI4LiteUART())
  val clint = Module(new AXI4LiteCLINT())

  // ==================================================================
  //                        1. IFU 路由与 PMA 保护
  // ==================================================================
  // IFU 只能访问 0x8000_0000 及以上的内存段
  val ifu_is_ram = io.ifu_bus.ar.bits.addr(31, 28) >= "h8".U
  
  // 连线到 RAM
  ram.io.ifu_bus.ar.valid := io.ifu_bus.ar.valid && ifu_is_ram
  ram.io.ifu_bus.ar.bits  := io.ifu_bus.ar.bits
  
  // PMA 错误处理：如果不是访问 RAM，直接返回 DECERR (b11)
  val ifu_err_valid = RegInit(false.B)
  when(io.ifu_bus.ar.valid && !ifu_is_ram && !ifu_err_valid) {
    ifu_err_valid := true.B
    printf("[PMA Violation] IFU tried to access %x!\n", io.ifu_bus.ar.bits.addr)
  }.elsewhen(io.ifu_bus.r.fire) {
    ifu_err_valid := false.B
  }

  io.ifu_bus.ar.ready := Mux(ifu_is_ram, ram.io.ifu_bus.ar.ready, !ifu_err_valid)
  io.ifu_bus.r.valid  := Mux(ifu_is_ram, ram.io.ifu_bus.r.valid, ifu_err_valid)
  io.ifu_bus.r.bits.data := Mux(ifu_is_ram, ram.io.ifu_bus.r.bits.data, 0.U)
  io.ifu_bus.r.bits.resp := Mux(ifu_is_ram, ram.io.ifu_bus.r.bits.resp, "b11".U) // b11 = DECERR
  ram.io.ifu_bus.r.ready := io.ifu_bus.r.ready

  // IFU 写通道屏蔽
  io.ifu_bus.aw.ready := false.B
  io.ifu_bus.w.ready  := false.B
  io.ifu_bus.b.valid  := false.B
  io.ifu_bus.b.bits   := DontCare
  ram.io.ifu_bus.aw := DontCare
  ram.io.ifu_bus.w  := DontCare
  ram.io.ifu_bus.b.ready := true.B

  // ==================================================================
  //                        2. LSU 1进3出 Xbar 路由
  // ==================================================================
  val laddr = io.lsu_bus.ar.bits.addr
  val waddr = io.lsu_bus.aw.bits.addr

  // 读地址路由 (优先级：UART > CLINT > RAM)
  // UART: 0xa000_03f8
  val is_uart_ar  = laddr === "ha00003f8".U
  // CLINT: 0x0200_0000 ~ 0x0200_ffff (前 16 位是 0x0200)
  val is_clint_ar = (laddr === "ha0000048".U) || (laddr === "ha000004c".U)
  // RAM: 0x8000_0000 以上，但排除 UART 和 CLINT
  val is_ram_ar   = !is_uart_ar && !is_clint_ar && laddr(31, 28) >= "h8".U

  // 写地址路由 (优先级：UART > CLINT > RAM)
  val is_uart_aw  = waddr === "ha00003f8".U
  val is_clint_aw = (waddr === "ha0000048".U) || (waddr === "ha000004c".U)
  val is_ram_aw   = !is_uart_aw && !is_clint_aw && waddr(31, 28) >= "h8".U

  // --- 发送请求 (Demux) ---
  // AR 通道
  ram.io.lsu_bus.ar.valid   := io.lsu_bus.ar.valid && is_ram_ar
  uart.io.bus.ar.valid      := io.lsu_bus.ar.valid && is_uart_ar
  clint.io.bus.ar.valid     := io.lsu_bus.ar.valid && is_clint_ar
  
  ram.io.lsu_bus.ar.bits    := io.lsu_bus.ar.bits
  uart.io.bus.ar.bits       := io.lsu_bus.ar.bits
  clint.io.bus.ar.bits      := io.lsu_bus.ar.bits

  io.lsu_bus.ar.ready := (ram.io.lsu_bus.ar.ready && is_ram_ar) || 
                         (uart.io.bus.ar.ready && is_uart_ar) || 
                         (clint.io.bus.ar.ready && is_clint_ar)

  // AW/W 通道 (Store)
  ram.io.lsu_bus.aw.valid   := io.lsu_bus.aw.valid && is_ram_aw
  uart.io.bus.aw.valid      := io.lsu_bus.aw.valid && is_uart_aw
  clint.io.bus.aw.valid     := io.lsu_bus.aw.valid && is_clint_aw

  ram.io.lsu_bus.w.valid    := io.lsu_bus.w.valid && is_ram_aw
  uart.io.bus.w.valid       := io.lsu_bus.w.valid && is_uart_aw
  clint.io.bus.w.valid      := io.lsu_bus.w.valid && is_clint_aw

  ram.io.lsu_bus.aw.bits    := io.lsu_bus.aw.bits
  uart.io.bus.aw.bits       := io.lsu_bus.aw.bits
  clint.io.bus.aw.bits      := io.lsu_bus.aw.bits
  
  ram.io.lsu_bus.w.bits     := io.lsu_bus.w.bits
  uart.io.bus.w.bits        := io.lsu_bus.w.bits
  clint.io.bus.w.bits       := io.lsu_bus.w.bits

  io.lsu_bus.aw.ready := (ram.io.lsu_bus.aw.ready && is_ram_aw) || 
                         (uart.io.bus.aw.ready && is_uart_aw) || 
                         (clint.io.bus.aw.ready && is_clint_aw)
  
  io.lsu_bus.w.ready  := (ram.io.lsu_bus.w.ready && is_ram_aw) || 
                         (uart.io.bus.w.ready && is_uart_aw) || 
                         (clint.io.bus.w.ready && is_clint_aw)

  // --- 接收响应 (Mux) ---
  // 由于每次只有一个 Slave 被激活，直接 OR 它们的 Valid
  io.lsu_bus.r.valid := ram.io.lsu_bus.r.valid || uart.io.bus.r.valid || clint.io.bus.r.valid
  io.lsu_bus.r.bits  := Mux(ram.io.lsu_bus.r.valid, ram.io.lsu_bus.r.bits,
                        Mux(uart.io.bus.r.valid, uart.io.bus.r.bits,
                            clint.io.bus.r.bits))
  
  ram.io.lsu_bus.r.ready := io.lsu_bus.r.ready
  uart.io.bus.r.ready    := io.lsu_bus.r.ready
  clint.io.bus.r.ready   := io.lsu_bus.r.ready

  io.lsu_bus.b.valid := ram.io.lsu_bus.b.valid || uart.io.bus.b.valid || clint.io.bus.b.valid
  io.lsu_bus.b.bits  := Mux(ram.io.lsu_bus.b.valid, ram.io.lsu_bus.b.bits,
                        Mux(uart.io.bus.b.valid, uart.io.bus.b.bits,
                            clint.io.bus.b.bits))
  
  ram.io.lsu_bus.b.ready := io.lsu_bus.b.ready
  uart.io.bus.b.ready    := io.lsu_bus.b.ready
  clint.io.bus.b.ready   := io.lsu_bus.b.ready
}