#include <am.h>
#include <klib-macros.h>

#define UART_BASE 0x10000000u
#define UART_RBR  (UART_BASE + 0x00)
#define UART_THR  (UART_BASE + 0x00)
#define UART_LSR  (UART_BASE + 0x05)
#define UART_LSR_DR   0x01u
#define UART_LSR_THRE 0x20u

void __am_timer_init();

void __am_timer_rtc(AM_TIMER_RTC_T *);
void __am_timer_uptime(AM_TIMER_UPTIME_T *);
void __am_input_keybrd(AM_INPUT_KEYBRD_T *);

static inline uint8_t uart_read8(uint32_t addr) {
  return *(volatile uint8_t *)addr;
}

static inline void uart_write8(uint32_t addr, uint8_t data) {
  *(volatile uint8_t *)addr = data;
}

static void __am_timer_config(AM_TIMER_CONFIG_T *cfg) { cfg->present = true; cfg->has_rtc = false; }
static void __am_input_config(AM_INPUT_CONFIG_T *cfg) { cfg->present = true; }
static void __am_uart_config(AM_UART_CONFIG_T *cfg) { cfg->present = true; }

static void __am_uart_tx(AM_UART_TX_T *uart) {
  while ((uart_read8(UART_LSR) & UART_LSR_THRE) == 0) {
  }
  uart_write8(UART_THR, (uint8_t)uart->data);
}

static void __am_uart_rx(AM_UART_RX_T *uart) {
  if (uart_read8(UART_LSR) & UART_LSR_DR) {
    uart->data = (char)uart_read8(UART_RBR);
  } else {
    uart->data = (char)0xff;
  }
}

static void fail(void *buf) { panic("access nonexist register"); }

bool ioe_init() {
  __am_timer_init();
  return true;
}

void ioe_read(int reg, void *buf) {
  switch (reg) {
    case AM_UART_CONFIG:  __am_uart_config((AM_UART_CONFIG_T *)buf); break;
    case AM_UART_RX:      __am_uart_rx((AM_UART_RX_T *)buf); break;
    case AM_TIMER_CONFIG: __am_timer_config((AM_TIMER_CONFIG_T *)buf); break;
    case AM_TIMER_RTC:    __am_timer_rtc((AM_TIMER_RTC_T *)buf); break;
    case AM_TIMER_UPTIME: __am_timer_uptime((AM_TIMER_UPTIME_T *)buf); break;
    case AM_INPUT_CONFIG: __am_input_config((AM_INPUT_CONFIG_T *)buf); break;
    case AM_INPUT_KEYBRD: __am_input_keybrd((AM_INPUT_KEYBRD_T *)buf); break;
    default:              fail(buf); break;
  }
}

void ioe_write(int reg, void *buf) {
  switch (reg) {
    case AM_UART_TX: __am_uart_tx((AM_UART_TX_T *)buf); break;
    default:         fail(buf); break;
  }
}
