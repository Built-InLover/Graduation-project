.PHONY: default sim clean

TOPNAME = ysyxSoCFull
YSYXSOC_HOME = $(abspath ../../ysyxSoC)
NPC_BUILD = $(abspath ../build)

VERILATOR = verilator
VERILATOR_CFLAGS += -MMD --build -cc \
	-O3 --x-assign fast --x-initial fast --noassert \
	--timescale "1ns/1ns" --no-timing

OBJ_DIR = ./obj_dir

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

default: sim

sim: $(VSRCS) $(CSRCS)
	@mkdir -p $(OBJ_DIR)
	$(VERILATOR) $(VERILATOR_CFLAGS) \
		--top-module $(TOPNAME) \
		$(addprefix -I, $(YSYXSOC_HOME)/perip/uart16550/rtl $(YSYXSOC_HOME)/perip/spi/rtl) \
		--trace-fst \
		--Mdir $(abspath $(OBJ_DIR)) --exe -o $(abspath $(OBJ_DIR)/$(TOPNAME)) \
		$(VSRCS) $(CSRCS)

run: sim
	$(OBJ_DIR)/$(TOPNAME)

clean:
	rm -rf $(OBJ_DIR)
