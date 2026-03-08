#include <am.h>
#include <klib-macros.h>

#define MAINARGS_MAX_LEN 64
#define MAINARGS_PLACEHOLDER "The insert-arg rule in Makefile will insert mainargs here."

extern char _heap_start;
extern char _heap_end;
int main(const char *args);

#define UART_BASE 0x10000000
#define UART_THR  (UART_BASE + 0x00)
#define UART_DLL  (UART_BASE + 0x00)
#define UART_DLM  (UART_BASE + 0x01)
#define UART_LCR  (UART_BASE + 0x03)
#define UART_LSR  (UART_BASE + 0x05)
#define UART_LSR_THRE 0x20
#define UART_LSR_TEMT 0x40

Area heap = RANGE(&_heap_start, &_heap_end);
static const char mainargs[MAINARGS_MAX_LEN] = MAINARGS_PLACEHOLDER;

//static void uart_wait_tx_idle() {
//  while ((*(volatile uint8_t *)UART_LSR & (UART_LSR_THRE | UART_LSR_TEMT)) != (UART_LSR_THRE | UART_LSR_TEMT)) {
//  }
//}
//
//static void uart_init() {
//  *(volatile uint8_t *)UART_LCR = 0x80;  // DLAB=1
//  *(volatile uint8_t *)UART_DLL = 1;     // divisor=1
//  *(volatile uint8_t *)UART_DLM = 0;
//  *(volatile uint8_t *)UART_LCR = 0x03;  // DLAB=0, 8N1
//}

void putch(char ch) {
  while (!(*(volatile uint8_t *)UART_LSR & 0x20));  // wait THRE
  *(volatile uint8_t *)UART_THR = ch;
}

void halt(int code) {
  asm volatile("mv a0, %0; ebreak" : :"r"(code));
  while (1);
}

void _trm_init() {
 // uart_wait_tx_idle();
 // uart_init();
 // uart_wait_tx_idle();
  int ret = main(mainargs);
  halt(ret);
}
