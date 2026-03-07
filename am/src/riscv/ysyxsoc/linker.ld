/*
 * 地址空间 (Address Space) — 二级 Bootloader + cpu-tests
 * -------------------------------------------------------------------------
 * [FLASH] (0x30000000 - 0x30FFFFFF)  <-- 所有代码/数据的存储区
 * |-----------------------|
 * |     *(fsbl)           |  <-- FSBL (VMA=LMA=Flash, XIP 执行)
 * |-----------------------|
 * |     .ssbl LMA         |  <-- SSBL 加载地址
 * |-----------------------|
 * |     .text LMA         |  <-- 程序代码加载地址
 * |-----------------------|
 * |     .rodata LMA       |  <-- 只读常量加载地址
 * |-----------------------|
 * |     .data.extra LMA   |  <-- RT-Thread 特殊 section 加载地址
 * |-----------------------|
 * |     .data LMA         |  <-- 初始化数据加载地址
 * |-----------------------|
 * -------------------------------------------------------------------------
 *
 * -------------------------------------------------------------------------
 * [SRAM] (0x0f000000 - 0x0f001FFF)  <-- SSBL + 程序运行 + 栈
 * |-----------------------| <--- 0x0f000000 (_ssbl_start)
 * |     .ssbl VMA         |  <-- SSBL 运行地址 (FSBL 从 Flash 搬来)
 * |-----------------------| <--- _ssbl_end
 * |     .text/.rodata     |  <-- 程序运行地址 (SSBL 从 Flash 搬来)
 * |     .data/.bss        |
 * |-----------------------| <--- _stack_pointer (0x0f002000)
 * |       Stack (栈)      |  <-- 向下增长
 * -------------------------------------------------------------------------
 *
 * -------------------------------------------------------------------------
 * [SDRAM] (0xa0000000 - 0xa03FFFFF)  <-- Heap / 测试内存
 * |-----------------------| <--- 0xa0000000 (_heap_start)
 * |       Heap (堆)       |
 * |-----------------------| <--- 0xa0400000 (_heap_end)
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
    _sram_start = .;
    *(.text*)
  } > SRAM AT > FLASH

  .rodata : {
    *(.rodata*)
    . = ALIGN(4);
  } > SRAM AT > FLASH

  .data.extra : {
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
  } > SRAM AT > FLASH

  .data : {
    _data_start = .;
    *(.data*)
    *(.sdata*)
    _data_end = .;
  } > SRAM AT > FLASH
  _sram_lma = LOADADDR(.text);

  .bss.extra : {
    _bss_start = .;
    . = ALIGN(8);
    __am_apps_bss_start = .;
    *(__am_apps.bss*)
    *(__am_apps.sbss*)
    *(__am_apps.scommon*)
    __am_apps_bss_end = .;
    . = ALIGN(8);
  } > SRAM

  .bss : {
    *(.bss*)
    *(.sbss*)
    *(.scommon)
    _bss_end = .;
  } > SRAM

  _heap_start         = ORIGIN(SDRAM);
  _boot_stack_pointer = ORIGIN(SRAM) + LENGTH(SRAM);
  _stack_pointer      = ORIGIN(SDRAM) + LENGTH(SDRAM);
  _heap_end           = _stack_pointer - 32K;
}
