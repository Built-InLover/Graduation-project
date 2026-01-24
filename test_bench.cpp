#include "VDistributedCore.h"
#include "VDistributedCore__Dpi.h"
#include "utils.h"
#include "verilated.h"
#include "verilated_fst_c.h"
#include "svdpi.h"

#include <common.h>
#include <cpu/decode.h>
#include <cpu/cpu.h>
#include <memory/paddr.h>
void init_monitor(int argc, char *argv[]);
void sdb_mainloop(void);                  

// --- 全局仿真对象 ---
VDistributedCore* top;
VerilatedFstC* tfp;
VerilatedContext* contextp;

static bool commit_flag = false;
static int dnpc = 0x80000000;

// --- 全局日志文件指针 ---
static FILE *log_fp = NULL; 

// --- 辅助函数1：记录内存访问 (由 DPI 调用) ---
void trace_mem(int type, uint32_t addr, uint32_t data, char mask) {
    if (log_fp == NULL) return;

    // 格式化输出：包含 PC, Inst, 以及原本的 Address, Data
    fprintf(log_fp, "\t[%s] Addr:0x%08x Data:0x%08x Mask:%d\n",
        type == 0 ? "READ" : "WRITE",
        addr,             // 读/写的地址
        data,             // 读/写的数据
        (int)mask
    );
    // fflush(log_fp); 
}

// --- 辅助函数2：记录指令提交 (由 isa_exec_once 调用) ---
void trace_inst(uint32_t pc, uint32_t inst) {
    if (log_fp == NULL) return;

    fprintf(log_fp, "[INST] Time:%-6ld PC:0x%08x Inst:0x%08x\n",
        contextp->time(), pc, inst
    );
    //fflush(log_fp); // 每次指令提交都刷新一次
}

// ============================================================================
// 1. DPI-C 导出函数 (由硬件 Verilog 调用)
// ============================================================================
void debug_after_one_inst();
extern "C" {
    void set_pc(int pc_val) { cpu.pc = (uint32_t)pc_val; }
    void set_dnpc(int dnpc_val) { dnpc = (uint32_t)dnpc_val; }

    void set_riscv_regs(const svLogicVecVal* regs) {
        // 注意：DPI 传输数组时，使用 aval 获取数据
        for (int i = 0; i < 32; i++) {
            cpu.gpr[i] = regs[i].aval;
        }
    }

    void check_commit(unsigned char over) { 
        commit_flag = (over != 0); 
    }

    // 处理 EBREAK
    void trap_handler(int pc) {
        // 1. 同步 NPC 状态机，让 sdb_mainloop 意识到程序已结束
        set_npc_state(NPC_END, (uint32_t)pc, cpu.gpr[10]);
        
        // 2. 打印彩色终端信息
        if (cpu.gpr[10] == 0) {
            printf("\033[1;32m[NPC] HIT GOOD TRAP at pc = 0x%08x\033[0m\n", (uint32_t)pc);
        } else {
            printf("\033[1;31m[NPC] HIT BAD TRAP at pc = 0x%08x, exit code = %d\033[0m\n", 
                    (uint32_t)pc, cpu.gpr[10]);
        }
        // 注意：此处不建议直接 exit(0)，让 main 里的循环自然结束以便清理资源
    }

    // 存储器接口
    int pmem_read(int addr) { 
        int data = (int)paddr_read((uint32_t)addr & ~0x3u, 4);
        trace_mem(0, (uint32_t)addr, (uint32_t)data, 0);
        return data;
    }
    
    void pmem_write(int addr, int data, char mask) {
        trace_mem(1, (uint32_t)addr, (uint32_t)data, mask);
        uint32_t aligned_addr = (uint32_t)addr & ~0x3u; 
        uint32_t u_data = (uint32_t)data;
        switch (mask & 0xF) {
            // 单字节写
            case 0b0001: paddr_write(aligned_addr + 0, 1, u_data & 0xFF);         break; 
            case 0b0010: paddr_write(aligned_addr + 1, 1, (u_data >> 8) & 0xFF);  break;
            case 0b0100: paddr_write(aligned_addr + 2, 1, (u_data >> 16) & 0xFF); break; 
            case 0b1000: paddr_write(aligned_addr + 3, 1, (u_data >> 24) & 0xFF); break; 
            // 半字写
            case 0b0011: paddr_write(aligned_addr + 0, 2, u_data & 0xFFFF);       break; 
            case 0b1100: paddr_write(aligned_addr + 2, 2, (u_data >> 16) & 0xFFFF);break; 
            // 全字写
            case 0b1111: paddr_write(aligned_addr + 0, 4, u_data);                break; 
            default: break;
        }
    }
}

// ============================================================================
// 2. 基础仿真原子操作
// ============================================================================
void one_cycle() {
   top->clock = 1;
   top->eval();
    if (tfp) {
        tfp->dump(contextp->time());
        contextp->timeInc(1);
        //tfp->flush();
    }
    top->clock = 0;
    top->eval(); 
    if (tfp) {
        tfp->dump(contextp->time());
        contextp->timeInc(1);
        tfp->flush(); 
    }
}

// ============================================================================
// 3. CPU 行为适配接口
// ============================================================================

void init_sim(int argc, char** argv) {
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    top = new VDistributedCore{contextp};

    Verilated::traceEverOn(true);
    tfp = new VerilatedFstC;
    top->trace(tfp, 99);
    tfp->open("obj_dir/DistributedCore.fst");

    // 打开全能日志文件
    log_fp = fopen("./obj_dir/simulation.log", "w");
    if (log_fp == NULL) {
        printf("Error: Can not open simulation.log\n");
    } else {
        printf("Info: Logging trace to simulation.log\n");
    }

    // 1. 进入复位状态
    top->reset = 1;
    top->clock = 0;
    top->eval();
    
    printf("\n\n--- NPC Simulation Init ---\n");
    printf("Step 1: Applying Reset...\n");

    // 2. 复位循环：只翻转时钟，不调用 one_cycle()
    // 这样做是为了避免在复位期间地址为 0 时触发 paddr_read()
    for (int i = 0; i < 10; i++) {
        // 下降沿
        top->clock = 1;
        top->eval();
        if (tfp) tfp->dump(contextp->time());
        contextp->timeInc(1);

        // 上升沿：PC 寄存器在此刻采样复位值 0x80000000
        top->clock = 0;
        top->eval();
        if (tfp) tfp->dump(contextp->time());
        contextp->timeInc(1);
    }

    // 3. 撤销复位
    top->reset = 0;
    // 撤销复位后必须 eval 一次，让组合逻辑根据 reset=0 和 PC 的新值重新计算
    top->eval(); 
   printf("Step 2: State initialized. Starting main simulation loop...\n");
    printf("---------------------------\n");
}

int isa_exec_once(struct Decode *s) {
    commit_flag = false;
    if(npc_state.state != NPC_RUNNING) return 0;
    while (!commit_flag && npc_state.state == NPC_RUNNING) {
        one_cycle();
        if(top->io_inst_over) commit_flag = true;
    }
    if(commit_flag) commit_flag = false;
    // 从硬件同步状态到 C++ cpu 结构体
    debug_after_one_inst();
    s->pc = cpu.pc;
    s->dnpc = dnpc;
    s->snpc = cpu.pc + 4;
    s->isa.inst = paddr_read(s->pc, 4); 
    // 写入日志 ---
    trace_inst(s->pc, s->isa.inst);
    //printf("inst=%08x, pc=%08x\n", s->isa.inst, s->pc);
    if(s->isa.inst == 0x00100073){ // EBREAK
        npc_state.state = NPC_END;
    }
    return 0;
}

int main(int argc, char** argv) {
    init_sim(argc, argv);
    init_monitor(argc, argv);

    sdb_mainloop(); // 框架会反复调用 isa_exec_once

    // --- 关闭日志 ---
    if (log_fp) {
        fclose(log_fp);
        log_fp = NULL;
    }

    if (tfp) tfp->close();
    printf("sim finished\n");
    return 0;
}
void debug_after_one_inst(){
    cpu.pc = top->io_debug_pc;
    dnpc = top->io_debug_dnpc;
    cpu.gpr[0]  = top->io_debug_regs_0;
    cpu.gpr[1]  = top->io_debug_regs_1;
    cpu.gpr[2]  = top->io_debug_regs_2;
    cpu.gpr[3]  = top->io_debug_regs_3;
    cpu.gpr[4]  = top->io_debug_regs_4;
    cpu.gpr[5]  = top->io_debug_regs_5;
    cpu.gpr[6]  = top->io_debug_regs_6;
    cpu.gpr[7]  = top->io_debug_regs_7;
    cpu.gpr[8]  = top->io_debug_regs_8;
    cpu.gpr[9]  = top->io_debug_regs_9;
    cpu.gpr[10] = top->io_debug_regs_10;
    cpu.gpr[11] = top->io_debug_regs_11;
    cpu.gpr[12] = top->io_debug_regs_12;
    cpu.gpr[13] = top->io_debug_regs_13;
    cpu.gpr[14] = top->io_debug_regs_14;
    cpu.gpr[15] = top->io_debug_regs_15;
    cpu.gpr[16] = top->io_debug_regs_16;
    cpu.gpr[17] = top->io_debug_regs_17;
    cpu.gpr[18] = top->io_debug_regs_18;
    cpu.gpr[19] = top->io_debug_regs_19;
    cpu.gpr[20] = top->io_debug_regs_20;
    cpu.gpr[21] = top->io_debug_regs_21;
    cpu.gpr[22] = top->io_debug_regs_22;
    cpu.gpr[23] = top->io_debug_regs_23;
    cpu.gpr[24] = top->io_debug_regs_24;
    cpu.gpr[25] = top->io_debug_regs_25;
    cpu.gpr[26] = top->io_debug_regs_26;
    cpu.gpr[27] = top->io_debug_regs_27;
    cpu.gpr[28] = top->io_debug_regs_28;
    cpu.gpr[29] = top->io_debug_regs_29;
    cpu.gpr[30] = top->io_debug_regs_30;
    cpu.gpr[31] = top->io_debug_regs_31;
}
