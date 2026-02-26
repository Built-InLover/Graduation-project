// flash-loader: runs from MROM, loads program from flash to SRAM via SPI, then jumps
#define SPI_BASE    0x10001000u
#define SPI_RX0     (*(volatile unsigned *)(SPI_BASE + 0x00))
#define SPI_TX0     (*(volatile unsigned *)(SPI_BASE + 0x00))
#define SPI_TX1     (*(volatile unsigned *)(SPI_BASE + 0x04))
#define SPI_CTRL    (*(volatile unsigned *)(SPI_BASE + 0x10))
#define SPI_DIVIDER (*(volatile unsigned *)(SPI_BASE + 0x14))
#define SPI_SS      (*(volatile unsigned *)(SPI_BASE + 0x18))

#define SPI_CTRL_ASS    (1u << 13)
#define SPI_CTRL_TX_NEG (1u << 10)
#define SPI_CTRL_GO     (1u <<  8)

#define SRAM_BASE   0x0f000000u
#define FLASH_SIZE_TO_LOAD 4096u  // load 4KB from flash

static unsigned bswap32(unsigned x) {
    return ((x & 0xFF) << 24) | ((x & 0xFF00) << 8) |
           ((x >> 8) & 0xFF00) | ((x >> 24) & 0xFF);
}

static unsigned flash_read(unsigned addr) {
    SPI_TX1 = (0x03u << 24) | (addr & 0x00FFFFFFu);
    SPI_TX0 = 0;
    SPI_DIVIDER = 0;
    SPI_SS = 0x01;
    SPI_CTRL = SPI_CTRL_ASS | SPI_CTRL_TX_NEG | SPI_CTRL_GO | 64;
    while (SPI_CTRL & SPI_CTRL_GO);
    return bswap32(SPI_RX0);
}

void _start() {
    volatile unsigned *dst = (volatile unsigned *)SRAM_BASE;
    for (unsigned off = 0; off < FLASH_SIZE_TO_LOAD; off += 4) {
        dst[off / 4] = flash_read(off);
    }
    // jump to SRAM entry point
    void (*entry)(void) = (void (*)(void))SRAM_BASE;
    entry();
}
