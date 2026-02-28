.section entry, "ax"
.globl _start
.type _start, @function

_start:
  mv s0, zero
  la sp, _stack_pointer

  # 搬运 .text + .rodata + .data 从 Flash(LMA) 到 SRAM(VMA)
  la a0, _sram_start       # SRAM 目标起始（.text VMA）
  la a1, _sram_lma         # Flash 源起始（.text LMA）
  la a2, _data_end         # SRAM 目标结束（.data VMA 末尾）
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

  # 绝对跳转到 SRAM 中的 _trm_init
  # 不能用 call（PC-relative 会跳回 Flash 地址）
  la t0, _trm_init
  jalr ra, t0, 0
