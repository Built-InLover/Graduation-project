// char-test for Flash XIP: loaded into flash, runs from 0x30000000
#define UART_BASE 0x10000000u

void _start() {
    *(volatile char *)UART_BASE = 'A';
    *(volatile char *)UART_BASE = '\n';
    asm volatile("ebreak");
}
