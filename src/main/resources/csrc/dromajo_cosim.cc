#include <vpi_user.h>
#include <svdpi.h>

#include "dromajo.h"

dromajo_t *dromajo = 0;

extern "C" int dromajo_init(
    char* binary_name,
    char* bootrom_name,
    char* reset_vector,
    char* dtb_name,
    char* mmio_start,
    char* mmio_end)
{
    dromajo = new dromajo_t(binary_name, bootrom_name, reset_vector, dtb_name, mmio_start, mmio_end);
    if (!(dromajo->valid_state())) {
        printf("[DEBUG] Failed Dromajo initialization\n");
        return 1;
    }

    return 0;
}

extern "C" int dromajo_step(
    int      hartid,
    uint64_t dut_pc,
    uint32_t dut_insn,
    uint64_t dut_wdata,
    uint64_t mstatus,
    bool     check)
{
    return dromajo->step(hartid, dut_pc, dut_insn, dut_wdata, mstatus, check);
}

extern "C" void dromajo_raise_trap(
    int     hartid,
    int64_t cause)
{
    dromajo->raise_trap(hartid, cause);
}
