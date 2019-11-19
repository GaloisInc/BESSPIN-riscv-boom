#include <vpi_user.h>
#include <svdpi.h>

#include <stdio.h>

#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include "dromajo_cosim.h"

#define MAX_ARGS 15

extern "C" dromajo_cosim_state_t* dromajo_cosim_init_wrapper(
        char* binary_name,
        char* bootrom_name,
        char* dtb_name,
        char* mmio_start,
        char* mmio_end)
{
    char *local_argv[MAX_ARGS];
    char local_argc = 0;

    local_argv[local_argc] = (char*)"./dromajo";
    local_argc += 1;
    local_argv[local_argc] = (char*)"--compact_bootrom";
    local_argc += 1;
    local_argv[local_argc] = (char*)"--reset_vector";
    local_argc += 1;
    local_argv[local_argc] = (char*)"0x00010040";
    local_argc += 1;

    if (strlen(binary_name) != 0) {
        local_argv[local_argc] = (char*)binary_name;
        local_argc += 1;
    }
    if (strlen(bootrom_name) != 0) {
        local_argv[local_argc] = (char*)"--bootrom";
        local_argc += 1;
        local_argv[local_argc] = (char*)bootrom_name;
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

    static dromajo_cosim_state_t* retval = dromajo_cosim_init(local_argc, local_argv);
    if (retval) {
        printf("[DEBUG] Completed Dromajo initialization\n");
    } else {
        printf("[DEBUG] Failed Dromajo initialization\n");
        exit(1);
    }

    return retval;
}
