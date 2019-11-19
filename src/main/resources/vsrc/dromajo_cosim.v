`define INST_LEN 32

import "DPI-C" function chandle dromajo_cosim_init_wrapper(
    input string binary_name_name,
    input string bootrom_name,
    input string dtb_name,
    input string mmio_start,
    input string mmio_end
);

import "DPI-C" function int dromajo_cosim_step(
    input chandle dromajo_cosim_state,
    input int     hartid,
    input longint dut_pc,
    input int     dut_insn,
    input longint dut_wdata,
    input longint mstatus,
    input bit     check
);

import "DPI-C" function void dromajo_cosim_raise_trap(
    input chandle dromajo_cosim_state,
    input int     hartid,
    input longint cause
);

import "DPI-C" function void dromajo_cosim_fini(
    input chandle dromajo_cosim_state
);

module DromajoCosimBlackbox
    #(parameter COMMIT_WIDTH, XLEN)
(
    input clock,
    input reset,

    input [          (COMMIT_WIDTH) - 1:0] valid  ,
    input [                  (XLEN) - 1:0] hartid ,
    input [     (XLEN*COMMIT_WIDTH) - 1:0] pc     ,
    input [(`INST_LEN*COMMIT_WIDTH) - 1:0] inst   ,
    input [     (XLEN*COMMIT_WIDTH) - 1:0] wdata  ,
    input [     (XLEN*COMMIT_WIDTH) - 1:0] mstatus,
    input [          (COMMIT_WIDTH) - 1:0] check  ,

    input           int_xcpt,
    input [XLEN - 1:0] cause
);
    static chandle dromajo_state;
    string binary_name, bootrom_name, dtb_name, mmio_start, mmio_end;
    int i, fail;

    initial begin
        $display("[DEBUG] Setting up Dromajo Cosimulation");
        if ($value$plusargs ("drj_binary_name=%s", binary_name)) begin
            $display("[DEBUG] binary_name=%s", binary_name);
        end
        if ($value$plusargs ("drj_bootrom_name=%s", bootrom_name)) begin
            $display("[DEBUG] bootrom_name=%s", bootrom_name);
        end
        if ($value$plusargs ("drj_dtb_name=%s", dtb_name)) begin
            $display("[DEBUG] dtb_name=%s", dtb_name);
        end
        if ($value$plusargs ("drj_mmio_start=%s", mmio_start)) begin
            $display("[DEBUG] mmio_start=%s", mmio_start);
        end
        if ($value$plusargs ("drj_mmio_end=%s", mmio_end)) begin
            $display("[DEBUG] mmio_end=%s", mmio_end);
        end
        dromajo_state = dromajo_cosim_init_wrapper(binary_name, bootrom_name, dtb_name, mmio_start, mmio_end);
        $display("[DEBUG] Done setting up Dromajo Cosimulation");
    end

    always @(posedge clock) begin
        if (!reset) begin
            for (i=0; i<COMMIT_WIDTH; i=i+1) begin
                if (valid[i]) begin
                    fail = dromajo_cosim_step(
                        dromajo_state,
                        hartid,
                        pc[((i+1)*XLEN - 1)-:XLEN],
                        inst[((i+1)*`INST_LEN - 1)-:`INST_LEN],
                        wdata[((i+1)*XLEN - 1)-:XLEN],
                        mstatus[((i+1)*XLEN - 1)-:XLEN],
                        check[i]);
                    if (fail) begin
                        $display("FAIL: Dromajo Simulation Failed");
                        dromajo_cosim_fini(dromajo_state);
                        $fatal;
                    end
                end
            end

            if (int_xcpt) begin
                dromajo_cosim_raise_trap(
                    dromajo_state,
                    hartid,
                    cause
                );
            end
        end
    end

endmodule
