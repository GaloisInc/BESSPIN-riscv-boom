#include "dromajo.h"

dromajo_t::dromajo_t(int argc, char *argv[])
{
    //for (int i = 0; i < argc; ++i)
    //    printf("[DEBUG] dromajo arg %d: %s\n", i, argv[i]);

    // call dromajo's init function
    this->state = dromajo_cosim_init(argc, argv);
}

dromajo_t::~dromajo_t()
{
    // call dromajo's finish function
    dromajo_cosim_fini(this->state);
}

int dromajo_t::step(
    int      hartid,
    uint64_t dut_pc,
    uint32_t dut_insn,
    uint64_t dut_wdata,
    uint64_t mstatus,
    bool     check)
{
    // call dromajo's step function
    return dromajo_cosim_step(this->state, hartid, dut_pc, dut_insn, dut_wdata, mstatus, check);
}

void dromajo_t::raise_trap(
    int     hartid,
    int64_t cause)
{
    // call dromajo's raise trap function
    dromajo_cosim_raise_trap(this->state, hartid, cause);
}

int dromajo_t::valid_state()
{
    return (this->state != 0);
}
