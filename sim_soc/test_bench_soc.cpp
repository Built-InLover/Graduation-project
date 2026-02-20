#include "VysyxSoCFull.h"
#include "verilated.h"
#include "verilated_fst_c.h"
#include <cstdio>
#include <cstdint>
#include <cassert>

// DPI-C 桩函数（ysyxSoC 外设需要）
extern "C" void flash_read(int32_t addr, int32_t *data) { assert(0); }
extern "C" void mrom_read(int32_t addr, int32_t *data) { assert(0); }

// UART 输出
extern "C" void uart_putchar(unsigned char c) { putchar(c); }

static VysyxSoCFull *top;
static VerilatedContext *contextp;
static VerilatedFstC *tfp;

void one_cycle() {
    top->clock = 1;
    top->eval();
    if (tfp) {
        tfp->dump(contextp->time());
        contextp->timeInc(1);
    }
    top->clock = 0;
    top->eval();
    if (tfp) {
        tfp->dump(contextp->time());
        contextp->timeInc(1);
    }
}

int main(int argc, char **argv) {
    contextp = new VerilatedContext;
    Verilated::commandArgs(argc, argv);
    contextp->commandArgs(argc, argv);
    top = new VysyxSoCFull{contextp};

    Verilated::traceEverOn(true);
    tfp = new VerilatedFstC;
    top->trace(tfp, 99);
    tfp->open("obj_dir/ysyxSoCFull.fst");

    // 复位
    top->reset = 1;
    top->clock = 0;
    for (int i = 0; i < 10; i++) {
        one_cycle();
    }
    top->reset = 0;

    printf("--- ysyxSoC Simulation Start ---\n");

    // 主循环
    for (int i = 0; i < 1000000; i++) {
        one_cycle();
    }

    printf("--- ysyxSoC Simulation End (%ld cycles) ---\n", contextp->time() / 2);

    if (tfp) tfp->close();
    delete top;
    delete contextp;
    return 0;
}
