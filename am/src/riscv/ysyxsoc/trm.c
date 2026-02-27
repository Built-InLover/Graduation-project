#include <am.h>
#include <klib-macros.h>

extern char _heap_start;
extern char _heap_end;
int main(const char *args);

#define UART_BASE 0x10000000
#define UART_THR  (UART_BASE + 0x00)
#define UART_DLL  (UART_BASE + 0x00)
#define UART_DLM  (UART_BASE + 0x01)
#define UART_LCR  (UART_BASE + 0x03)
#define UART_LSR  (UART_BASE + 0x05)

Area heap = RANGE(&_heap_start, &_heap_end);

static void uart_init() {
  *(volatile uint8_t *)UART_LCR = 0x80;  // DLAB=1
  *(volatile uint8_t *)UART_DLL = 1;     // divisor=1
  *(volatile uint8_t *)UART_DLM = 0;
  *(volatile uint8_t *)UART_LCR = 0x03;  // DLAB=0, 8N1
}

void putch(char ch) {
  while (!(*(volatile uint8_t *)UART_LSR & 0x20));  // wait THRE
  *(volatile uint8_t *)UART_THR = ch;
}

void halt(int code) {
  asm volatile("mv a0, %0; ebreak" : :"r"(code));
  while (1);
}

void _trm_init() {
  uart_init();
  int ret = main("");
  halt(ret);
}
