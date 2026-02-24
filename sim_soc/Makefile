.PHONY: default sim clean verilog

TOPNAME = ysyxSoCFull
YSYXSOC_HOME = $(abspath ../../ysyxSoC)
NPC_BUILD = $(abspath ../build)

VERILATOR = verilator
VERILATOR_CFLAGS += -MMD --build -cc \
	-O3 --x-assign fast --x-initial fast --noassert \
	--timescale "1ns/1ns" --no-timing --autoflush

ifdef TRACE
VERILATOR_CFLAGS += --trace-fst
CFLAGS_EXTRA += -DTRACE_ON
endif

ifdef LOG
CFLAGS_EXTRA += -DLOG_ON
endif

ifdef MAX_CYCLES
CFLAGS_EXTRA += -DMAX_CYCLES=$(MAX_CYCLES)
endif

ifdef DIFFTEST
CFLAGS_EXTRA += -DDIFFTEST_ON
LDFLAGS_EXTRA += -ldl
endif

OBJ_DIR = ./obj_dir

# Cross-compile toolchain
CROSS_COMPILE = riscv64-linux-gnu-
CC_RV = $(CROSS_COMPILE)gcc
OBJCOPY = $(CROSS_COMPILE)objcopy

# Verilog sources
# 1. ysyxSoCFull (顶层)
VSRCS += $(YSYXSOC_HOME)/build/ysyxSoCFull.v
# 2. CPU 核心
VSRCS += $(NPC_BUILD)/ysyx_23060000.sv
# 3. ysyxSoC 外设
VSRCS += $(shell find $(YSYXSOC_HOME)/perip -name "*.v")

# C++ sources
CSRCS = $(abspath ./test_bench_soc.cpp)

# Include paths
INC_FLAGS += -I$(YSYXSOC_HOME)/perip/uart16550/rtl
INC_FLAGS += -I$(YSYXSOC_HOME)/perip/spi/rtl

GENERATED_SV = $(NPC_BUILD)/ysyx_23060000.sv

# 生成 Verilog 并修正 Chisel 信号命名 → ysyxSoC 接口格式
verilog:
	cd $(abspath ..) && mill playground.runMain top.main_ysyxsoc
	@# 去掉 _bits_ 前缀，合并通道握手信号名（aw_valid → awvalid 等）
	sed -i 's/_bits_//g' $(GENERATED_SV)
	sed -i 's/io_master_\(aw\|w\|b\|ar\|r\)_\(valid\|ready\)/io_master_\1\2/g' $(GENERATED_SV)
	sed -i 's/io_slave_\(aw\|w\|b\|ar\|r\)_\(valid\|ready\)/io_slave_\1\2/g' $(GENERATED_SV)
	sed -i '/^\/\/ ----- 8< ----- FILE "firrtl_black_box_resource_files.f"/,$$d' $(GENERATED_SV)

default: sim

char-test.bin: char-test.c mrom.ld
	$(CC_RV) -nostdlib -nostartfiles -O2 -T mrom.ld -march=rv32i -mabi=ilp32 -o char-test.elf $<
	$(OBJCOPY) -O binary char-test.elf $@

sim: $(VSRCS) $(CSRCS)
	@mkdir -p $(OBJ_DIR)
	$(VERILATOR) $(VERILATOR_CFLAGS) \
		--top-module $(TOPNAME) \
		$(addprefix -I, $(YSYXSOC_HOME)/perip/uart16550/rtl $(YSYXSOC_HOME)/perip/spi/rtl) \
		$(if $(CFLAGS_EXTRA),-CFLAGS "$(CFLAGS_EXTRA)") \
		$(if $(LDFLAGS_EXTRA),-LDFLAGS "$(LDFLAGS_EXTRA)") \
		--Mdir $(abspath $(OBJ_DIR)) --exe -o $(abspath $(OBJ_DIR)/$(TOPNAME)) \
		$(VSRCS) $(CSRCS)

run: sim
	$(OBJ_DIR)/$(TOPNAME) $(IMG) $(DIFF)

clean:
	rm -rf $(OBJ_DIR) char-test.elf char-test.bin
