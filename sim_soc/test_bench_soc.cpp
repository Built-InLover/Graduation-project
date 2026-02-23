#include "VysyxSoCFull.h"
#include "verilated.h"
#include "verilated_fst_c.h"
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cassert>

// MROM 缓冲区（4KB，与硬件一致）
static uint8_t mrom_data[4096];

// DPI-C 桩函数（ysyxSoC 外设需要）
extern "C" void flash_read(int32_t addr, int32_t *data) { assert(0); }
extern "C" void mrom_read(int32_t addr, int32_t *data) {
    uint32_t offset = (uint32_t)addr - 0x20000000;
    if (offset + 4 <= sizeof(mrom_data)) {
        memcpy(data, mrom_data + offset, 4);
    } else {
        *data = 0;
    }
}

// ebreak 终止机制
static bool ebreak_flag = false;
extern "C" void sim_ebreak() { ebreak_flag = true; }

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
    const char *bin_file = (argc > 1) ? argv[1] : "char-test.bin";
    FILE *fp = fopen(bin_file, "rb");
    if (!fp) { fprintf(stderr, "cannot open %s\n", bin_file); return 1; }
    size_t n = fread(mrom_data, 1, sizeof(mrom_data), fp);
    fclose(fp);
    printf("Loaded %zu bytes into MROM\n", n);

    contextp = new VerilatedContext;
    Verilated::commandArgs(argc, argv);
    contextp->commandArgs(argc, argv);
    top = new VysyxSoCFull{contextp};

    Verilated::traceEverOn(true);
    tfp = new VerilatedFstC;
    top->trace(tfp, 99);
    tfp->open("obj_dir/ysyxSoCFull.fst");

    top->reset = 1;
    top->clock = 0;
    for (int i = 0; i < 10; i++) {
        one_cycle();
    }
    top->reset = 0;

    printf("--- ysyxSoC Simulation Start ---\n");

    for (int i = 0; i < 1000000; i++) {
        one_cycle();
        if (ebreak_flag) {
            printf("ebreak detected at cycle %d\n", i);
            break;
        }
    }

    printf("--- ysyxSoC Simulation End (%ld cycles) ---\n", contextp->time() / 2);

    if (tfp) tfp->close();
    delete top;
    delete contextp;
    return 0;
}
