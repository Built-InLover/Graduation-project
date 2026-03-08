/*
 * 地址空间 (Address Space) — 二级 Bootloader + SDRAM execute test
 * -------------------------------------------------------------------------
 * [FLASH] (0x30000000 - 0x30FFFFFF)  <-- 所有代码/数据的存储区
 * |-----------------------|
 * |     *(fsbl)           |  <-- FSBL (VMA=LMA=Flash, XIP 执行)
 * |-----------------------|
 * |     .ssbl LMA         |  <-- SSBL 加载地址
 * |-----------------------|
 * | .text/.rodata/.data LMA | <-- SDRAM 运行镜像，LMA 必须连续
 * |-----------------------|
 * -------------------------------------------------------------------------
 *
 * -------------------------------------------------------------------------
 * [SRAM] (0x0f000000 - 0x0f001FFF)  <-- 仅 SSBL + boot stack
 * |-----------------------| <--- 0x0f000000 (_ssbl_start)
 * |     .ssbl VMA         |
 * |-----------------------| <--- _ssbl_end
 * |     boot stack        |  <-- _boot_stack_pointer = 0x0f002000
 * -------------------------------------------------------------------------
 *
 * -------------------------------------------------------------------------
 * [SDRAM] (0xa0000000 - 0xa03FFFFF)  <-- 程序运行 + 栈 + 堆
 * |-----------------------| <--- 0xa0000000 (_sdram_start)
 * |     .text/.rodata     |
 * |     .data/.bss        |
 * |-----------------------|
 * |       Heap (堆)       |  <-- _heap_start ~ _heap_end
 * |-----------------------| <--- _heap_end (_stack_pointer - 32K)
 * |       Stack (栈)      |  <-- _stack_pointer = 0xa0400000
 * -------------------------------------------------------------------------
 */

ENTRY(_start)

MEMORY {
  FLASH (rx)  : ORIGIN = 0x30000000, LENGTH = 16M
  SRAM  (rwx) : ORIGIN = 0x0f000000, LENGTH = 8K
  SDRAM (rwx) : ORIGIN = 0xa0000000, LENGTH = 4M
}

SECTIONS {
  .fsbl : {
    *(fsbl)
  } > FLASH

  .ssbl : {
    _ssbl_start = .;
    *(ssbl)
    _ssbl_end = .;
  } > SRAM AT > FLASH
  _ssbl_lma = LOADADDR(.ssbl);

  .text : {
    _sdram_start = .;
    *(.text*)
  } > SDRAM AT > FLASH

  .rodata : {
    *(.rodata*)
    . = ALIGN(8);
  } > SDRAM AT > FLASH

  .data : {
    _data_start = .;

    . = ALIGN(8);
    __fsymtab_start = .;
    KEEP(*(FSymTab))
    __fsymtab_end = .;

    . = ALIGN(8);
    __vsymtab_start = .;
    KEEP(*(VSymTab))
    __vsymtab_end = .;

    . = ALIGN(8);
    __rt_init_start = .;
    KEEP(*(SORT(.rti_fn*)))
    __rt_init_end = .;

    . = ALIGN(8);
    __rt_utest_tc_tab_start = .;
    KEEP(*(UtestTcTab))
    __rt_utest_tc_tab_end = .;

    . = ALIGN(8);
    __am_apps_data_start = .;
    *(__am_apps.data*)
    *(__am_apps.sdata*)
    __am_apps_data_end = .;

    . = ALIGN(8);
    *(.data*)
    *(.sdata*)
    _data_end = .;
  } > SDRAM AT > FLASH
  _sdram_lma = LOADADDR(.text);

  .bss.extra : {
    _bss_start = .;
    . = ALIGN(8);
    __am_apps_bss_start = .;
    *(__am_apps.bss*)
    *(__am_apps.sbss*)
    *(__am_apps.scommon*)
    __am_apps_bss_end = .;
    . = ALIGN(8);
  } > SDRAM

  .bss : {
    *(.bss*)
    *(.sbss*)
    *(.scommon)
    _bss_end = .;
  } > SDRAM

  _heap_start         = _bss_end;
  _boot_stack_pointer = ORIGIN(SRAM) + LENGTH(SRAM);
  _stack_pointer      = ORIGIN(SDRAM) + LENGTH(SDRAM);
  _heap_end           = _stack_pointer - 32K;
}
