AM_SRCS := riscv/ysyxsoc/start.S \
           riscv/ysyxsoc/trm.c \
           riscv/ysyxsoc/cte.c \
           riscv/ysyxsoc/trap.S \
           riscv/ysyxsoc/ioe.c \
           riscv/ysyxsoc/timer.c \
           riscv/ysyxsoc/input.c \
           platform/dummy/vme.c \
           platform/dummy/mpe.c

# 每个函数/变量放独立 section，配合 --gc-sections 丢弃未引用代码，压缩 bin 体积
CFLAGS    += -fdata-sections -ffunction-sections
LDSCRIPTS += $(AM_HOME)/am/src/riscv/ysyxsoc/linker.ld
LDFLAGS   += --gc-sections -e _start
# linker.ld 已合并 RT-Thread extra.ld 的 section，去掉原始 extra.ld 避免冲突
LDFLAGS   := $(filter-out -T extra.ld,$(LDFLAGS))

MAINARGS_MAX_LEN = 64
MAINARGS_PLACEHOLDER = The insert-arg rule in Makefile will insert mainargs here.

# sim_soc 仿真环境路径
YSYXSOC_SIM_HOME ?= /home/lj/ysyx-workbench/Graduation-project/sim_soc

image: image-dep
	@$(OBJDUMP) -d $(IMAGE).elf > $(IMAGE).txt
	@echo + OBJCOPY "->" $(IMAGE_REL).bin
	@$(OBJCOPY) -S --set-section-flags .bss=alloc,contents -O binary $(IMAGE).elf $(IMAGE).bin

insert-arg: image
	@python3 $(AM_HOME)/tools/insert-arg.py $(IMAGE).bin $(MAINARGS_MAX_LEN) "$(MAINARGS_PLACEHOLDER)" "$(mainargs)"

YSYXSOC_RUN_TARGET := run
ifeq ($(NVBOARD),1)
YSYXSOC_RUN_TARGET := run-nvboard
endif

run: insert-arg
	$(MAKE) -C $(YSYXSOC_SIM_HOME) $(YSYXSOC_RUN_TARGET) \
		IMG=$(IMAGE).bin \
		$(if $(DIFFTEST),DIFFTEST=$(DIFFTEST)) \
		$(if $(DIFF),DIFF=$(DIFF))

.PHONY: insert-arg
