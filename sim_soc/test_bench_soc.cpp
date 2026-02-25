#include "VysyxSoCFull.h"
#include "verilated.h"
#ifdef TRACE_ON
#include "verilated_fst_c.h"
#endif
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cassert>

#ifdef DIFFTEST_ON
#include <dlfcn.h>
#endif

#ifndef MAX_CYCLES
#define MAX_CYCLES 1000000
#endif

// MROM 缓冲区（4KB，与硬件一致）
static uint8_t mrom_data[4096];

// DPI-C 桩函数（ysyxSoC 外设需要）
extern "C" void flash_read(int32_t addr, int32_t *data) { assert(0); }
extern "C" void mrom_read(int32_t addr, int32_t *data) {
    // 对齐到 4 字节边界：MROM 硬件返回整个 word，由 master 侧按 addr[1:0] 提取字节
    uint32_t offset = ((uint32_t)addr - 0x20000000) & ~0x3u;
    if (offset + 4 <= sizeof(mrom_data)) {
        memcpy(data, mrom_data + offset, 4);
    } else {
        *data = 0;
    }
}

// ebreak 终止机制
static bool ebreak_flag = false;
extern "C" void sim_ebreak() { ebreak_flag = true; }

// ==================== DiffTest ====================
#ifdef DIFFTEST_ON
enum { DIFFTEST_TO_DUT, DIFFTEST_TO_REF };

typedef struct {
    uint32_t gpr[32];
    uint32_t pc;
    struct { uint32_t mtvec, mepc, mstatus, mcause; } csr;
} CPU_state;

static CPU_state npc_cpu = {};
static bool difftest_commit = false;

static void (*ref_difftest_memcpy)(uint32_t, void*, size_t, bool);
static void (*ref_difftest_regcpy)(void*, bool);
static void (*ref_difftest_exec)(uint64_t);

extern "C" void sim_set_gpr(int idx, int val) {
    npc_cpu.gpr[idx] = (uint32_t)val;
}

// NPC debug_csr: [0]=mcause, [1]=mepc, [2]=mstatus, [3]=mtvec
// NEMU CSR struct: { mtvec, mepc, mstatus, mcause }
extern "C" void sim_difftest(int pc, int dnpc, int mcause, int mepc, int mstatus, int mtvec) {
    npc_cpu.pc = (uint32_t)dnpc;
    npc_cpu.csr.mcause  = (uint32_t)mcause;
    npc_cpu.csr.mepc    = (uint32_t)mepc;
    npc_cpu.csr.mstatus = (uint32_t)mstatus;
    npc_cpu.csr.mtvec   = (uint32_t)mtvec;
    difftest_commit = true;
}

static const char *reg_names[] = {
    "zero","ra","sp","gp","tp","t0","t1","t2",
    "s0","s1","a0","a1","a2","a3","a4","a5",
    "a6","a7","s2","s3","s4","s5","s6","s7",
    "s8","s9","s10","s11","t3","t4","t5","t6"
};

static bool difftest_check() {
    CPU_state ref_cpu = {};
    ref_difftest_exec(1);
    ref_difftest_regcpy(&ref_cpu, DIFFTEST_TO_DUT);

    bool pass = true;
    if (ref_cpu.pc != npc_cpu.pc) {
        printf("[difftest] PC mismatch: ref=0x%08x npc=0x%08x\n", ref_cpu.pc, npc_cpu.pc);
        pass = false;
    }
    for (int i = 0; i < 32; i++) {
        if (ref_cpu.gpr[i] != npc_cpu.gpr[i]) {
            printf("[difftest] GPR %s(x%d) mismatch: ref=0x%08x npc=0x%08x\n",
                   reg_names[i], i, ref_cpu.gpr[i], npc_cpu.gpr[i]);
            pass = false;
        }
    }
    if (ref_cpu.csr.mcause != npc_cpu.csr.mcause) {
        printf("[difftest] mcause mismatch: ref=0x%08x npc=0x%08x\n", ref_cpu.csr.mcause, npc_cpu.csr.mcause);
        pass = false;
    }
    if (ref_cpu.csr.mepc != npc_cpu.csr.mepc) {
        printf("[difftest] mepc mismatch: ref=0x%08x npc=0x%08x\n", ref_cpu.csr.mepc, npc_cpu.csr.mepc);
        pass = false;
    }
    if (ref_cpu.csr.mstatus != npc_cpu.csr.mstatus) {
        printf("[difftest] mstatus mismatch: ref=0x%08x npc=0x%08x\n", ref_cpu.csr.mstatus, npc_cpu.csr.mstatus);
        pass = false;
    }
    if (ref_cpu.csr.mtvec != npc_cpu.csr.mtvec) {
        printf("[difftest] mtvec mismatch: ref=0x%08x npc=0x%08x\n", ref_cpu.csr.mtvec, npc_cpu.csr.mtvec);
        pass = false;
    }
    if (!pass) {
        printf("[difftest] === Full GPR Dump ===\n");
        for (int i = 0; i < 32; i++) {
            printf("  x%-2d(%-4s): ref=0x%08x npc=0x%08x %s\n",
                   i, reg_names[i], ref_cpu.gpr[i], npc_cpu.gpr[i],
                   (ref_cpu.gpr[i] != npc_cpu.gpr[i]) ? "<< MISMATCH" : "");
        }
        printf("  PC       : ref=0x%08x npc=0x%08x %s\n",
               ref_cpu.pc, npc_cpu.pc,
               (ref_cpu.pc != npc_cpu.pc) ? "<< MISMATCH" : "");
        printf("  mcause   : ref=0x%08x npc=0x%08x\n", ref_cpu.csr.mcause, npc_cpu.csr.mcause);
        printf("  mepc     : ref=0x%08x npc=0x%08x\n", ref_cpu.csr.mepc, npc_cpu.csr.mepc);
        printf("  mstatus  : ref=0x%08x npc=0x%08x\n", ref_cpu.csr.mstatus, npc_cpu.csr.mstatus);
        printf("  mtvec    : ref=0x%08x npc=0x%08x\n", ref_cpu.csr.mtvec, npc_cpu.csr.mtvec);
    }
    difftest_commit = false;
    return pass;
}

static void init_difftest(const char *ref_so, size_t img_size) {
    void *handle = dlopen(ref_so, RTLD_LAZY);
    if (!handle) { fprintf(stderr, "dlopen failed: %s\n", dlerror()); assert(0); }

    ref_difftest_memcpy = (decltype(ref_difftest_memcpy))dlsym(handle, "difftest_memcpy");
    ref_difftest_regcpy = (decltype(ref_difftest_regcpy))dlsym(handle, "difftest_regcpy");
    ref_difftest_exec   = (decltype(ref_difftest_exec))dlsym(handle, "difftest_exec");
    void (*ref_difftest_init)(int) = (void(*)(int))dlsym(handle, "difftest_init");
    assert(ref_difftest_memcpy && ref_difftest_regcpy && ref_difftest_exec && ref_difftest_init);

    ref_difftest_init(0);
    ref_difftest_memcpy(0x20000000, mrom_data, img_size, DIFFTEST_TO_REF);

    // 初始化 NPC 状态并同步到 REF
    npc_cpu.pc = 0x20000000;
    npc_cpu.csr.mstatus = 0x1800;
    ref_difftest_regcpy(&npc_cpu, DIFFTEST_TO_REF);

    printf("[difftest] initialized with %s\n", ref_so);
}
#else
extern "C" void sim_set_gpr(int idx, int val) {}
extern "C" void sim_difftest(int pc, int dnpc, int mcause, int mepc, int mstatus, int mtvec) {}
#endif

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
    const char *diff_so  = (argc > 2) ? argv[2] : NULL;

    FILE *fp = fopen(bin_file, "rb");
    if (!fp) { fprintf(stderr, "cannot open %s\n", bin_file); return 1; }
    size_t n = fread(mrom_data, 1, sizeof(mrom_data), fp);
    fclose(fp);
    printf("Loaded %zu bytes into MROM\n", n);

#ifdef DIFFTEST_ON
    if (diff_so) {
        init_difftest(diff_so, n);
    } else {
        fprintf(stderr, "DIFFTEST_ON but no ref .so provided (argv[2])\n");
        return 1;
    }
#endif

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
#ifdef DIFFTEST_ON
        if (difftest_commit) {
            if (!difftest_check()) {
                printf("[difftest] FAIL at cycle %d\n", i);
#ifdef TRACE_ON
                tfp->close();
#endif
                delete top;
                delete contextp;
                return 1;
            }
        }
#endif
    }

    printf("--- ysyxSoC Simulation End (%ld cycles) ---\n", contextp->time() / 2);

#ifdef TRACE_ON
    tfp->close();
#endif
    delete top;
    delete contextp;
    return 0;
}
