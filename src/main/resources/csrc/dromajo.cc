#include <stdio.h>

//#include <unistd.h>
//#include <stdlib.h>
//#include <string.h>

#include "dromajo.h"

dromajo_t::dromajo_t(
    char* binary_name,
    char* bootrom_name,
    char* reset_vector,
    char* dtb_name,
    char* mmio_start,
    char* mmio_end) {

    // setup arguments
    char *local_argv[MAX_ARGS];
    char local_argc = 0;

    local_argv[local_argc] = (char*)"./dromajo";
    local_argc += 1;
    local_argv[local_argc] = (char*)"--compact_bootrom";
    local_argc += 1;
    local_argv[local_argc] = (char*)"--reset_vector";
    local_argc += 1;
    local_argv[local_argc] = (char*)reset_vector;
    local_argc += 1;
    local_argv[local_argc] = (char*)"--bootrom";
    local_argc += 1;
    local_argv[local_argc] = (char*)bootrom_name;
    local_argc += 1;

    if (strlen(binary_name) != 0) {
        local_argv[local_argc] = (char*)binary_name;
        local_argc += 1;
    }
    if (strlen(dtb_name) != 0) {
        local_argv[local_argc] = (char*)"--dtb";
        local_argc += 1;
        local_argv[local_argc] = (char*)dtb_name;
        local_argc += 1;
    }
    if (strlen(mmio_start) != 0) {
        local_argv[local_argc] = (char*)"--mmio_start";
        local_argc += 1;
        local_argv[local_argc] = (char*)mmio_start;
        local_argc += 1;
    }
    if (strlen(mmio_end) != 0) {
        local_argv[local_argc] = (char*)"--mmio_end";
        local_argc += 1;
        local_argv[local_argc] = (char*)mmio_end;
        local_argc += 1;
    }

    if (MAX_ARGS < local_argc) {
        printf("[DEBUG] Too many arguments\n");
        exit(1);
    }

    // call dromajo's init function
    this->state = dromajo_cosim_init(local_argc, local_argv);
}

dromajo_t::~dromajo_t() {
    // call dromajo's finish function
    dromajo_cosim_fini(this->state);
}

int dromajo_t::step(
    int      hartid,
    uint64_t dut_pc,
    uint32_t dut_insn,
    uint64_t dut_wdata,
    uint64_t mstatus,
    bool     check) {

    // call dromajo's step function
    return dromajo_cosim_step(this->state, hartid, dut_pc, dut_insn, dut_wdata, mstatus, check);
}

void dromajo_t::raise_trap(
    int     hartid,
    int64_t cause) {

    // call dromajo's raise trap function
    dromajo_cosim_raise_trap(this->state, hartid, cause);
}

int dromajo_t::valid_state() {
    return (this->state != 0);
}
