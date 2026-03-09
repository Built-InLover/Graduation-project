#include <am.h>
#include <klib-macros.h>
#include <stdint.h>

#define UART_BASE 0x10000000u
#define UART_RBR  (UART_BASE + 0x00)
#define UART_THR  (UART_BASE + 0x00)
#define UART_LSR  (UART_BASE + 0x05)
#define UART_LSR_DR   0x01u
#define UART_LSR_THRE 0x20u

#define VGA_FB_BASE   0x21000000u
#define VGA_WIDTH     640
#define VGA_HEIGHT    480
#define VGA_FB_WORDS  (VGA_WIDTH * VGA_HEIGHT)
#define VGA_FB_SIZE   (VGA_FB_WORDS * (int)sizeof(uint32_t))

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

static inline volatile uint32_t *vga_fb(void) {
  return (volatile uint32_t *)(uintptr_t)VGA_FB_BASE;
}

static void __am_timer_config(AM_TIMER_CONFIG_T *cfg) { cfg->present = true; cfg->has_rtc = false; }
static void __am_input_config(AM_INPUT_CONFIG_T *cfg) { cfg->present = true; }
static void __am_uart_config(AM_UART_CONFIG_T *cfg) { cfg->present = true; }

static void __am_gpu_config(AM_GPU_CONFIG_T *cfg) {
  *cfg = (AM_GPU_CONFIG_T) {
    .present = true,
    .has_accel = false,
    .width = VGA_WIDTH,
    .height = VGA_HEIGHT,
    .vmemsz = VGA_FB_SIZE,
  };
}

static void __am_gpu_status(AM_GPU_STATUS_T *status) {
  status->ready = true;
}

static void __am_gpu_fbdraw(AM_GPU_FBDRAW_T *ctl) {
  int src_x = 0;
  int src_y = 0;
  int dst_x = ctl->x;
  int dst_y = ctl->y;
  int draw_w = ctl->w;
  int draw_h = ctl->h;
  uint32_t *pixels = (uint32_t *)ctl->pixels;

  if (pixels == NULL || draw_w <= 0 || draw_h <= 0) {
    return;
  }

  if (dst_x < 0) {
    src_x = -dst_x;
    draw_w += dst_x;
    dst_x = 0;
  }
  if (dst_y < 0) {
    src_y = -dst_y;
    draw_h += dst_y;
    dst_y = 0;
  }
  if (dst_x >= VGA_WIDTH || dst_y >= VGA_HEIGHT) {
    return;
  }
  if (dst_x + draw_w > VGA_WIDTH) {
    draw_w = VGA_WIDTH - dst_x;
  }
  if (dst_y + draw_h > VGA_HEIGHT) {
    draw_h = VGA_HEIGHT - dst_y;
  }
  if (draw_w <= 0 || draw_h <= 0) {
    return;
  }

  volatile uint32_t *fb = vga_fb();
  for (int row = 0; row < draw_h; row++) {
    int fb_row = dst_y + row;
    int src_row = src_y + row;
    volatile uint32_t *dst = fb + fb_row * VGA_WIDTH + dst_x;
    uint32_t *src = pixels + src_row * ctl->w + src_x;
    for (int col = 0; col < draw_w; col++) {
      dst[col] = src[col];
    }
  }
}

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
    case AM_GPU_CONFIG:   __am_gpu_config((AM_GPU_CONFIG_T *)buf); break;
    case AM_GPU_STATUS:   __am_gpu_status((AM_GPU_STATUS_T *)buf); break;
    default:              fail(buf); break;
  }
}

void ioe_write(int reg, void *buf) {
  switch (reg) {
    case AM_UART_TX:    __am_uart_tx((AM_UART_TX_T *)buf); break;
    case AM_GPU_FBDRAW: __am_gpu_fbdraw((AM_GPU_FBDRAW_T *)buf); break;
    default:            fail(buf); break;
  }
}
