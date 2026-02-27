.section entry, "ax"
.globl _start
.type _start, @function

_start:
  mv s0, zero
  la sp, _stack_pointer

  # 搬运 .data 从 FLASH(LMA) 到 SRAM(VMA)
  la a0, _data_start
  la a1, _data_lma
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

  call _trm_init
