#include "VysyxSoCFull.h"
#include "verilated.h"
#ifdef TRACE_ON
#include "verilated_fst_c.h"
#endif
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cassert>

#ifndef MAX_CYCLES
#define MAX_CYCLES 10000
#endif

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

// 调试日志 DPI-C
extern "C" void sim_itrace(int pc, int dnpc) {
#ifdef LOG_ON
    printf("[itrace] pc=0x%08x dnpc=0x%08x\n", (uint32_t)pc, (uint32_t)dnpc);
#endif
}

extern "C" void sim_mtrace(int pc, int addr, int data, char is_write, int size) {
#ifdef LOG_ON
    printf("[mtrace] pc=0x%08x %c addr=0x%08x data=0x%08x size=%d\n",
           (uint32_t)pc, is_write ? 'W' : 'R', (uint32_t)addr, (uint32_t)data, size);
#endif
}

extern "C" void sim_regtrace(int pc, int rd, int wdata) {
#ifdef LOG_ON
    printf("[regtrace] pc=0x%08x x%d <- 0x%08x\n", (uint32_t)pc, rd, (uint32_t)wdata);
#endif
}

static VysyxSoCFull *top;
static VerilatedContext *contextp;
#ifdef TRACE_ON
static VerilatedFstC *tfp;
#endif

void one_cycle() {
    top->clock = 1;
    top->eval();
#ifdef TRACE_ON
    tfp->dump(contextp->time());
#endif
    contextp->timeInc(1);
    top->clock = 0;
    top->eval();
#ifdef TRACE_ON
    tfp->dump(contextp->time());
#endif
    contextp->timeInc(1);
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

#ifdef TRACE_ON
    Verilated::traceEverOn(true);
    tfp = new VerilatedFstC;
    top->trace(tfp, 99);
    tfp->open("obj_dir/ysyxSoCFull.fst");
#endif

    top->reset = 1;
    top->clock = 0;
    for (int i = 0; i < 10; i++) {
        one_cycle();
    }
    top->reset = 0;

    printf("--- ysyxSoC Simulation Start ---\n");

    for (int i = 0; i < MAX_CYCLES; i++) {
        one_cycle();
        if (ebreak_flag) {
            printf("ebreak detected at cycle %d\n", i);
            break;
        }
    }

    printf("--- ysyxSoC Simulation End (%ld cycles) ---\n", contextp->time() / 2);

#ifdef TRACE_ON
    tfp->close();
#endif
    delete top;
    delete contextp;
    return 0;
}
