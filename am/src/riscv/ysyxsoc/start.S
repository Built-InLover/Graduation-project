# ===== 汇编 UART 辅助宏 =====
# UART16550 基地址 0x10000000
# THR=+0, LCR=+3, LSR=+5, DLL=+0(DLAB=1), DLM=+1(DLAB=1)

.macro uart_init_asm
  li   t1, 0x10000000
  li   t2, 0x80          # DLAB=1
  sb   t2, 3(t1)         # LCR = 0x80
  li   t2, 1
  sb   t2, 0(t1)         # DLL = 1
  sb   zero, 1(t1)       # DLM = 0
  li   t2, 0x03          # 8N1, DLAB=0
  sb   t2, 3(t1)         # LCR = 0x03
.endm

# 轮询 LSR[5](THRE) 后写字符到 THR
.macro uart_putc ch
  li   t1, 0x10000000
  li   t3, \ch
1983:
  lbu  t2, 5(t1)         # LSR
  andi t2, t2, 0x20      # THRE bit
  beqz t2, 1983b
  sb   t3, 0(t1)         # THR
.endm

# ===== FSBL: Flash XIP 执行 =====
.section fsbl, "ax"
.globl _start
.type _start, @function

_start:
  mv s0, zero
  la sp, _stack_pointer

  # 初始化 UART（后续 trm.c 会重复初始化，无影响）
  uart_init_asm
  uart_putc 'F'           # FSBL started

  # 搬运 SSBL 从 Flash(LMA) 到 SRAM(VMA)
  la a0, _ssbl_start
  la a1, _ssbl_lma
  la a2, _ssbl_end
1:
  bge a0, a2, 2f
  lw t0, 0(a1)
  sw t0, 0(a0)
  addi a0, a0, 4
  addi a1, a1, 4
  j 1b
2:
  uart_putc 'S'           # SSBL copied to SRAM

  # 跳转到 SRAM 中的 SSBL
  la t0, _ssbl_entry
  jalr zero, t0, 0

# ===== SSBL: SRAM 执行 =====
.section ssbl, "ax"
.globl _ssbl_entry
.type _ssbl_entry, @function

_ssbl_entry:
  # 搬运 .text+.rodata+.data 从 Flash(LMA) 到 PSRAM(VMA)
  la a0, _psram_start
  la a1, _psram_lma
  la a2, _data_end
1:
  bge a0, a2, 2f
  lw t0, 0(a1)
  sw t0, 0(a0)
  addi a0, a0, 4
  addi a1, a1, 4
  j 1b
2:

  # 清零 .bss
  la a0, _bss_start
  la a1, _bss_end
3:
  bge a0, a1, 4f
  sw zero, 0(a0)
  addi a0, a0, 4
  j 3b
4:
  uart_putc 'M'           # Main program ready in PSRAM
  uart_putc '\n'

  # 跳转到 PSRAM 中的 _trm_init
  la t0, _trm_init
  jalr ra, t0, 0
