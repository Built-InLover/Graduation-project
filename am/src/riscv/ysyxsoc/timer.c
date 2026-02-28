#include <am.h>
#include <stdint.h>

#define CLINT_BASE 0x02000000
#define MTIME_ADDR (CLINT_BASE + 0xBFF8)

static inline uint32_t inl(uintptr_t addr) {
  return *(volatile uint32_t *)addr;
}

void __am_timer_init() {
}

void __am_timer_uptime(AM_TIMER_UPTIME_T *uptime) {
  uint64_t mtime;
  mtime = inl(MTIME_ADDR + 4);  // high 32-bit
  mtime <<= 32;
  mtime += inl(MTIME_ADDR);     // low 32-bit
  uptime->us = mtime;  // treat each tick as 1 microsecond
}

void __am_timer_rtc(AM_TIMER_RTC_T *rtc) {
  rtc->second = 0;
  rtc->minute = 0;
  rtc->hour   = 0;
  rtc->day    = 0;
  rtc->month  = 0;
  rtc->year   = 1900;
}
