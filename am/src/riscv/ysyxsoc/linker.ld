/*
 * 地址空间 (Address Space) — 二级 Bootloader + RT-Thread
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
 * [SRAM] (0x0f000000 - 0x0f001FFF)  <-- 仅 SSBL 临时执行
 * |-----------------------| <--- 0x0f000000 (_ssbl_start)
 * |     .ssbl VMA         |  <-- SSBL 运行地址 (FSBL 从 Flash 搬来)
 * |-----------------------| <--- _ssbl_end
 * |       (空闲)          |
 * |-----------------------| <--- 0x0f002000
 * -------------------------------------------------------------------------
 *
 * -------------------------------------------------------------------------
 * [PSRAM] (0x80000000 - 0x803FFFFF)  <-- 程序运行 + 栈 + 堆
 * |-----------------------| <--- 0x80000000 (_psram_start)
 * |     .text VMA         |  <-- 程序代码运行地址 (SSBL 从 Flash 搬来)
 * |-----------------------|
 * |     .rodata VMA       |
 * |-----------------------|
 * |     .data.extra VMA   |  <-- RT-Thread: FSymTab/VSymTab/.rti_fn/UtestTcTab/am_apps.data
 * |-----------------------|
 * |     .data/.sdata VMA  |
 * |-----------------------| <--- _data_end
 * |     .bss.extra        |  <-- RT-Thread: am_apps.bss
 * |-----------------------|
 * |       .bss            |
 * |-----------------------| <--- _bss_end
 * |          |            |
 * |          V            |
 * |       Stack (栈)      |  <-- 向下增长, 32KB
 * |-----------------------| <--- _stack_pointer (0x80400000)
 * |       Heap (堆)       |  <-- _bss_end ~ _heap_end (栈底之前)
 * |-----------------------| <--- _heap_end (_stack_pointer - 32K)
 * -------------------------------------------------------------------------
 */

ENTRY(_start)

MEMORY {
  FLASH (rx)  : ORIGIN = 0x30000000, LENGTH = 16M
  SRAM  (rwx) : ORIGIN = 0x0f000000, LENGTH = 8K
  PSRAM (rwx) : ORIGIN = 0x80000000, LENGTH = 4M
}

SECTIONS {
  /* FSBL: VMA=LMA=Flash, XIP 执行 */
  .fsbl : {
    *(fsbl)
  } > FLASH

  /* SSBL: VMA=SRAM, LMA=Flash, FSBL 搬运 */
  .ssbl : {
    _ssbl_start = .;
    *(ssbl)
    _ssbl_end = .;
  } > SRAM AT > FLASH
  _ssbl_lma = LOADADDR(.ssbl);

  /* 程序: VMA=PSRAM, LMA=Flash, SSBL 搬运 */
  .text : {
    _psram_start = .;
    *(.text*)
  } > PSRAM AT > FLASH

  .rodata : {
    *(.rodata*)
    . = ALIGN(4);
  } > PSRAM AT > FLASH

  /* RT-Thread 特殊 data section（INSERT BEFORE .data 的等价物） */
  .data.extra : {
    . = ALIGN(8);
    /* finsh shell */
    __fsymtab_start = .;
    KEEP(*(FSymTab))
    __fsymtab_end = .;
    . = ALIGN(8);
    __vsymtab_start = .;
    KEEP(*(VSymTab))
    __vsymtab_end = .;
    . = ALIGN(8);

    /* RT-Thread auto-init */
    __rt_init_start = .;
    KEEP(*(SORT(.rti_fn*)))
    __rt_init_end = .;
    . = ALIGN(8);

    /* utest */
    __rt_utest_tc_tab_start = .;
    KEEP(*(UtestTcTab))
    __rt_utest_tc_tab_end = .;

    /* AM apps data */
    . = ALIGN(8);
    __am_apps_data_start = .;
    *(__am_apps.data*)
    *(__am_apps.sdata*)
    __am_apps_data_end = .;
    . = ALIGN(8);
  } > PSRAM AT > FLASH

  .data : {
    _data_start = .;
    *(.data*)
    *(.sdata*)
    _data_end = .;
  } > PSRAM AT > FLASH
  _psram_lma = LOADADDR(.text);

  /* RT-Thread 特殊 bss section（INSERT BEFORE .bss 的等价物） */
  .bss.extra : {
    _bss_start = .;
    . = ALIGN(8);
    __am_apps_bss_start = .;
    *(__am_apps.bss*)
    *(__am_apps.sbss*)
    *(__am_apps.scommon*)
    __am_apps_bss_end = .;
    . = ALIGN(8);
  } > PSRAM

  .bss : {
    *(.bss*)
    *(.sbss*)
    *(.scommon)
    _bss_end = .;
  } > PSRAM

  _heap_start    = _bss_end;
  _stack_pointer = ORIGIN(PSRAM) + LENGTH(PSRAM);
  _heap_end      = _stack_pointer - 32K;
}
