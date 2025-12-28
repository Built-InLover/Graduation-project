#include "Vtop.h"
#include "Vtop__Dpi.h"
#include "verilated.h"
#include "verilated_fst_c.h"
#include "svdpi.h"

#include <common.h>
#include <cpu/decode.h>
#include <cpu/cpu.h>
#include <memory/paddr.h>

// --- 全局仿真对象 ---
Vtop* top;
VerilatedFstC* tfp;
VerilatedContext* contextp;

// --- 仿真状态记录 ---
struct BusState {
    bool pending = false;
    uint32_t data_latch = 0;
};
static BusState ifu_state, lsu_state;
static bool commit_flag = false;

// ============================================================================
// 1. DPI-C 导出函数 (由硬件 Verilog 调用)
// ============================================================================

extern "C" {
    void set_pc(int pc_val) { cpu.pc = (uint32_t)pc_val; }
    void set_dnpc(int dnpc_val) { cpu.dnpc = (uint32_t)dnpc_val; }

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
    int pmem_read(int addr) { return (int)paddr_read((uint32_t)addr & ~0x3u, 4); }
    
    void pmem_write(int addr, int data, char mask) {
        uint32_t u_addr = (uint32_t)addr;
        uint32_t u_data = (uint32_t)data;
        switch (mask & 0xF) {
            case 0b0001: paddr_write(u_addr + 0, 1, u_data & 0xFF);         break; 
            case 0b0010: paddr_write(u_addr + 1, 1, (u_data >> 8) & 0xFF);  break; 
            case 0b0100: paddr_write(u_addr + 2, 1, (u_data >> 16) & 0xFF); break; 
            case 0b1000: paddr_write(u_addr + 3, 1, (u_data >> 24) & 0xFF); break; 
            case 0b0011: paddr_write(u_addr + 0, 2, u_data & 0xFFFF);       break; 
            case 0b1100: paddr_write(u_addr + 2, 2, (u_data >> 16) & 0xFFFF);break; 
            case 0b1111: paddr_write(u_addr + 0, 4, u_data);                break; 
            default: break;
        }
    }
}

// ============================================================================
// 2. 基础仿真原子操作
// ============================================================================

void one_cycle() {
    top->clock = 0;
    top->eval();

    top->io_imem_bus_req_ready = 1;
    top->io_dmem_bus_req_ready = 1;

    if (top->io_imem_bus_req_valid) {
        ifu_state.data_latch = (uint32_t)paddr_read(top->io_imem_bus_req_addr, 4);
        ifu_state.pending = true;
    }

    if (top->io_dmem_bus_req_valid) {
        if (top->io_dmem_bus_req_wen) {
            pmem_write(top->io_dmem_bus_req_addr, top->io_dmem_bus_req_wdata, top->io_dmem_bus_req_wmask);
            lsu_state.data_latch = 0;
        } else {
            lsu_state.data_latch = (uint32_t)paddr_read(top->io_dmem_bus_req_addr, 4);
        }
        lsu_state.pending = true;
    }

    top->io_imem_bus_resp_valid = ifu_state.pending;
    top->io_imem_bus_resp_data  = ifu_state.data_latch;
    top->io_dmem_bus_resp_valid = lsu_state.pending;
    top->io_dmem_bus_resp_data  = lsu_state.data_latch;

    top->clock = 1;
    top->eval();

    if (top->io_imem_bus_resp_valid && top->io_imem_bus_resp_ready) ifu_state.pending = false;
    if (top->io_dmem_bus_resp_valid && top->io_dmem_bus_resp_ready) lsu_state.pending = false;

    if (tfp) tfp->dump(contextp->time());
    contextp->timeInc(1);
}

// ============================================================================
// 3. CPU 行为适配接口
// ============================================================================

void init_sim(int argc, char** argv) {
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    top = new Vtop{contextp};
    
    Verilated::traceEverOn(true);
    tfp = new VerilatedFstC;
    top->trace(tfp, 99);
    tfp->open("build/npc.fst");

    top->reset = 1;
    for (int i = 0; i < 10; i++) one_cycle();
    top->reset = 0;
    
    // 复位后清理总线状态，防止复位期间的杂波被误判为请求
    ifu_state.pending = false;
    lsu_state.pending = false;
}

int isa_exec_once(struct Decode *s) {
    commit_flag = false;

    // 运行硬件直到有指令退休或 CPU 停止运行
    while (!commit_flag && npc_state.state == NPC_RUNNING) {
        one_cycle();
    }

    s->pc = cpu.pc;
    s->dnpc = cpu.dnpc;
    s->isa.inst = paddr_read(s->pc, 4); 
    return 0;
}

int main(int argc, char** argv) {
    init_sim(argc, argv);
    init_monitor(argc, argv);

    sdb_mainloop(); // 框架会反复调用 isa_exec_once

    // 结束后多跑几拍波形，方便观察 ebreak 后的余波
    for(int i=0; i<5; i++) one_cycle();

    if (tfp) tfp->close();
    printf("Simulation finished.\n");
    return 0;
}