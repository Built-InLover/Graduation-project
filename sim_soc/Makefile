.PHONY: default sim sim-nvboard clean verilog run run-nvboard

TOPNAME = ysyxSoCFull
YSYXSOC_HOME = $(abspath ../ysyxSoC)
PLAYGROUND_HOME = $(abspath ../playground)
NPC_BUILD = $(abspath ../build)
MILL ?= $(abspath ../mill)
NVBOARD_HOME ?= /home/lj/ysyx-workbench/nvboard
NXDC_FILES = $(abspath ./constr/ysyxSoCFull.nxdc)
SRC_AUTO_BIND = $(abspath ./build/auto_bind.cpp)
USE_NVBOARD ?= 0

VERILATOR = verilator
VERILATOR_CFLAGS += -MMD --build -cc \
	-O3 --x-assign fast --x-initial fast --noassert \
	--timescale "1ns/1ns" --no-timing --autoflush

ifdef TRACE
VERILATOR_CFLAGS += --trace-fst
CXXFLAGS += -DTRACE_ON
endif

ifdef LOG
CXXFLAGS += -DLOG_ON
endif

ifdef LIMIT_CYCLE
CXXFLAGS += -DMAX_CYCLES=$(LIMIT_CYCLE)
endif

ifdef DIFFTEST
CXXFLAGS += -DDIFFTEST_ON
endif

# 不同配置使用不同 obj_dir，避免 DIFFTEST/非 DIFFTEST 互相覆盖
OBJ_SUFFIX := $(if $(DIFFTEST),_dt)$(if $(TRACE),_trace)
ifeq ($(USE_NVBOARD),1)
OBJ_SUFFIX += _nvboard
endif
OBJ_DIR = ./obj_dir$(OBJ_SUFFIX)

# Verilog sources
# 1. ysyxSoCFull (顶层)
VSRCS += $(YSYXSOC_HOME)/build/ysyxSoCFull.v
# 2. CPU 核心
VSRCS += $(NPC_BUILD)/ysyx_23060000.sv
# 3. ysyxSoC 外设
VSRCS += $(shell find $(YSYXSOC_HOME)/perip -name "*.v")

# C++ sources
ifeq ($(USE_NVBOARD),1)
CSRCS = $(abspath ./test_bench_soc_nvboard.cpp) $(SRC_AUTO_BIND)
else
CSRCS = $(abspath ./test_bench_soc.cpp)
endif

# Include paths
INC_PATH += $(YSYXSOC_HOME)/perip/uart16550/rtl
INC_PATH += $(YSYXSOC_HOME)/perip/spi/rtl

GENERATED_SV = $(NPC_BUILD)/ysyx_23060000.sv
PLAYGROUND_SCALA_SRCS := $(shell find $(PLAYGROUND_HOME)/src -name "*.scala")

$(SRC_AUTO_BIND): $(NXDC_FILES)
	@mkdir -p $(dir $@)
	python3 $(NVBOARD_HOME)/scripts/auto_pin_bind.py $^ $@

ifeq ($(USE_NVBOARD),1)
include $(NVBOARD_HOME)/scripts/nvboard.mk
endif

CXXFLAGS += $(addprefix -I, $(INC_PATH))

ifdef DIFFTEST
LDFLAGS += -ldl
endif

# 生成 Verilog 并修正 Chisel 信号命名 → ysyxSoC 接口格式
$(GENERATED_SV): $(PLAYGROUND_SCALA_SRCS)
	cd $(abspath ..) && $(MILL) -i playground.runMain top.main_ysyxsoc
	@# 去掉 _bits_ 前缀，合并通道握手信号名（aw_valid → awvalid 等）
	sed -i 's/_bits_//g' $(GENERATED_SV)
	sed -i 's/io_master_\(aw\|w\|b\|ar\|r\)_\(valid\|ready\)/io_master_\1\2/g' $(GENERATED_SV)
	sed -i 's/io_slave_\(aw\|w\|b\|ar\|r\)_\(valid\|ready\)/io_slave_\1\2/g' $(GENERATED_SV)
	sed -i '/^\/\/ ----- 8< ----- FILE "firrtl_black_box_resource_files.f"/,$$d' $(GENERATED_SV)

verilog: $(GENERATED_SV)

default: sim

sim: $(VSRCS) $(CSRCS) $(if $(filter 1,$(USE_NVBOARD)),$(NVBOARD_ARCHIVE))
	@mkdir -p $(OBJ_DIR)
	$(VERILATOR) $(VERILATOR_CFLAGS) \
		--top-module $(TOPNAME) \
		$(addprefix -I, $(YSYXSOC_HOME)/perip/uart16550/rtl $(YSYXSOC_HOME)/perip/spi/rtl) \
		$(addprefix -CFLAGS , $(CXXFLAGS)) \
		$(addprefix -LDFLAGS , $(if $(filter 1,$(USE_NVBOARD)),$(NVBOARD_ARCHIVE)) $(LDFLAGS)) \
		--Mdir $(abspath $(OBJ_DIR)) --exe -o $(abspath $(OBJ_DIR)/$(TOPNAME)) \
		$(VSRCS) $(CSRCS)

sim-nvboard:
	$(MAKE) USE_NVBOARD=1 sim $(if $(IMG),IMG=$(IMG)) $(if $(DIFFTEST),DIFFTEST=$(DIFFTEST)) $(if $(DIFF),DIFF=$(DIFF)) $(if $(FLASH),FLASH=$(FLASH))

# 用法: make run IMG=xxx.bin [DIFFTEST=1 DIFF=path/to/nemu.so] [FLASH=flash.bin]
run: sim
	$(OBJ_DIR)/$(TOPNAME) $(IMG) $(if $(DIFF),$(DIFF),$(if $(FLASH),none)) $(FLASH)

run-nvboard:
	$(MAKE) USE_NVBOARD=1 run $(if $(IMG),IMG=$(IMG)) $(if $(DIFFTEST),DIFFTEST=$(DIFFTEST)) $(if $(DIFF),DIFF=$(DIFF)) $(if $(FLASH),FLASH=$(FLASH))

clean:
	rm -rf obj_dir obj_dir_* build/auto_bind.cpp
