AM_SRCS := riscv/ysyxsoc/start.S \
           riscv/ysyxsoc/trm.c \
           platform/dummy/vme.c \
           platform/dummy/mpe.c

# 每个函数/变量放独立 section，配合 --gc-sections 丢弃未引用代码，压缩 bin 体积
CFLAGS    += -fdata-sections -ffunction-sections
LDSCRIPTS += $(AM_HOME)/am/src/riscv/ysyxsoc/linker.ld
LDFLAGS   += --gc-sections -e _start

# sim_soc 仿真环境路径
YSYXSOC_SIM_HOME ?= /home/lj/ysyx-workbench/Graduation-project/sim_soc

image: image-dep
	@$(OBJDUMP) -d $(IMAGE).elf > $(IMAGE).txt
	@echo + OBJCOPY "->" $(IMAGE_REL).bin
	@$(OBJCOPY) -S --set-section-flags .bss=alloc,contents -O binary $(IMAGE).elf $(IMAGE).bin

run: image
	$(MAKE) -C $(YSYXSOC_SIM_HOME) run \
		IMG=$(IMAGE).bin \
		$(if $(DIFFTEST),DIFFTEST=$(DIFFTEST)) \
		$(if $(DIFF),DIFF=$(DIFF))
