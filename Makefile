BUILD_DIR = ./build

PRJ = playground
MILL ?= ./mill

test:
	$(MILL) -i $(PRJ).test

verilog:
	mkdir -p $(BUILD_DIR)
	$(MILL) -i $(PRJ).runMain top.main --target-dir $(BUILD_DIR)
	head -n -2 ./build/top.sv > temp.sv && mv temp.sv ./build/top.sv
	cp ./build/top.sv ~/ysyx-workbench/npc/vsrc/

help:
	$(MILL) -i $(PRJ).runMain Elaborate --help

reformat:
	$(MILL) -i __.reformat

checkformat:
	$(MILL) -i __.checkFormat

bsp:
	$(MILL) -i mill.bsp.BSP/install

idea:
	$(MILL) -i mill.idea.GenIdea/idea

clean:
	-rm -rf $(BUILD_DIR)

.PHONY: test verilog help reformat checkformat clean

sim:
	@echo "Write this Makefile by yourself."

-include ../Makefile
