ENTRY(_start)

MEMORY {
  MROM (rx)  : ORIGIN = 0x20000000, LENGTH = 4K
  SRAM (rwx) : ORIGIN = 0x0f000000, LENGTH = 8K
}

SECTIONS {
  .text : {
    *(entry)
    *(.text*)
  } > MROM

  .rodata : {
    *(.rodata*)
    . = ALIGN(4);
  } > MROM

  .data : {
    _data_start = .;
    *(.data*)
    _data_end = .;
  } > SRAM AT > MROM

  _data_lma = LOADADDR(.data);

  .bss : {
    _bss_start = .;
    *(.bss*)
    *(.sbss*)
    *(.scommon)
    _bss_end = .;
  } > SRAM

  _stack_top = ALIGN(0x1000);
  . = _stack_top + 0x1000;
  _stack_pointer = .;
  _heap_start = .;
}
