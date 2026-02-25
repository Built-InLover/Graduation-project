#include <am.h>
#include <klib-macros.h>

extern char _heap_start;
extern char _stack_top;
int main(const char *args);

#define UART_BASE 0x10000000

Area heap = RANGE(&_heap_start, &_stack_top);

void putch(char ch) {
  *(volatile char *)UART_BASE = ch;
}

void halt(int code) {
  asm volatile("mv a0, %0; ebreak" : :"r"(code));
  while (1);
}

void _trm_init() {
  int ret = main("");
  halt(ret);
}
