// char-test for SRAM: loaded by flash-loader, runs from 0x0f000000
#define UART_BASE 0x10000000u

void _start() {
    *(volatile char *)UART_BASE = 'A';
    *(volatile char *)UART_BASE = '\n';
    asm volatile("ebreak");
}
