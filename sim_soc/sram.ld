ENTRY(_start)
SECTIONS {
  . = 0x0f000000;
  .text : { *(.text*) }
  .data : { *(.data*) }
  .bss  : { *(.bss*) }
}
