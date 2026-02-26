// xip-jump: runs from MROM, jumps to flash XIP address 0x30000000
void _start() {
    void (*entry)(void) = (void (*)(void))0x30000000u;
    entry();
}
