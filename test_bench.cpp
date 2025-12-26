#include "Vtop.h"
#include "common.h"
#include "riscv32.h"
#include "verilated.h"
#include "verilated_fst_c.h"
#include "Vtop__Dpi.h"
#include "svdpi.h"
#include <cstdint>
#include <cstdio>
#include <cpu/decode.h>
#include <cpu/cpu.h>
#include <memory/paddr.h>
#define MEM_BASE 0x80000000
#define MEM_SIZE 0x8000000
void init_monitor(int argc, char *argv[]);
void sdb_mainloop(void);

svBitVecVal pmem_read(const svLogicVecVal* raddr);
void pmem_write(const svLogicVecVal* waddr, const svLogicVecVal* wdata, char wmask);
void read_reg(const svLogicVecVal signal[32]);
//void read_pc(const svLogicVecVal* signal);

void ebreak(const char *a);

void init(Vtop *top);
void program_exit(void);
void one_cycle(Vtop *top,VerilatedFstC* tfp);

uint32_t pc;
int count = 0;
uint8_t sign_end;
bool mem_valid = 0;

Vtop *top = new Vtop("top");
VerilatedFstC* tfp = new VerilatedFstC;
int main(int argc, char** argv) {
  Verilated::traceEverOn(true);
  top->trace(tfp, 99);
  tfp->open(CURRENT_DIR);
	init(top);
	init_monitor(argc, argv);
	//cpu_exec(-1);
	sdb_mainloop();
  tfp->close();
  delete tfp; // 释放内存
  delete top;
  return 0;
}
void read_reg(const svLogicVecVal signal[32]){
    int i;                                    
    for (i=0; i<32; i++){                     
    	cpu.gpr[i] = signal[i].aval;      
    }                                     
		//cpu.csr.mcause  = ;
		//cpu.csr.mepc    = ;
		//cpu.csr.mstatus = ;
		//cpu.csr.mtvec   = ;
}                                             
//void read_pc(const svLogicVecVal* signal){
//	  pc = signal->aval;
//}

void init(Vtop *top){
	top->reset = 1;
	top->clock = 0;
	top->eval();
	top->clock = 1;
	top->eval();
	//top->clock = 0;
    top->reset = 0;
}
void one_cycle(Vtop *top,VerilatedFstC* tfp){
	top->clock = 0;
	top->eval();
	tfp->dump(count++);
	top->clock = 1;
	top->eval();
	tfp->dump(count++);
}
void ebreak(const char *a){
	set_npc_state(NPC_END, cpu.pc, 0);//此时的cpu.pc还没有更新为下一次的dnpc,程序结束后，就是dnpc了
	sign_end = 1;
	//program_exit();
}
int isa_exec_once(struct Decode *s){
	if(sign_end != 1)top->io_instr = paddr_read(s->pc,4);
//	if(s->pc == 0x8001565c)ebreak("a");
	if(top->io_instr == 0x00100073)ebreak("a");
	sign_end = 0;
	s->isa.inst = top->io_instr;
	s->snpc = s->pc + 4;
//dnpc should be updated after negedge
  top->clock = 0;    
	mem_valid = 1;
  top->eval();       
  tfp->dump(count++);
	s->dnpc = top->io_dnpc;
  top->clock = 1;    
	mem_valid = 0;
  top->eval();       
  tfp->dump(count++);

	return 0;
}
void program_exit(void){
  tfp->close();
	delete tfp;
	delete top;  
	exit(0);
}

extern "C" int pmem_read(int addr) {
    // 这种实现不考虑地址对齐检查，简单快速
    return mem[(uint32_t)addr >> 2];
}
extern "C" void pmem_write(int addr, int data, char mask) {
    uint32_t index = (uint32_t)addr >> 2;
    uint32_t old_data = mem[index];
    uint32_t new_data = 0;

    // 针对每一位 mask 进行判断
    for (int i = 0; i < 4; i++) {
        if ((mask >> i) & 0x1) {
            // 如果该字节掩码为 1，取新数据的对应字节
            new_data |= (data & (0xFF << (i * 8)));
        } else {
            // 如果该字节掩码为 0，保持旧数据的对应字节
            new_data |= (old_data & (0xFF << (i * 8)));
        }
    }
    mem[index] = new_data;
}
