#ifndef __DROMAJO_H
#define __DROMAJO_H

#include "dromajo_cosim.h"

#define MAX_ARGS 15

class dromajo_t
{
    public:
        dromajo_t(
            char* binary_name,
            char* bootrom_name,
            char* reset_vector,
            char* dtb_name,
            char* mmio_start,
            char* mmio_end
        );

        ~dromajo_t();

        int step(
            int      hartid,
            uint64_t dut_pc,
            uint32_t dut_insn,
            uint64_t dut_wdata,
            uint64_t mstatus,
            bool     check
        );

        void raise_trap(
            int     hartid,
            int64_t cause
        );

        int valid_state();

    private:
        dromajo_cosim_state_t *state;
}

#endif // __DROMAJO_H
