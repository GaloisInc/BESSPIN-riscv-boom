`define INST_LEN 32

import "DPI-C" function int dromajo_init(
    input string binary_name,
    input string bootrom_name,
    input string reset_vector,
    input string dtb_name,
    input string mmio_start,
    input string mmio_end
);

import "DPI-C" function int dromajo_step(
    input int     hartid,
    input longint dut_pc,
    input int     dut_insn,
    input longint dut_wdata,
    input longint mstatus,
    input bit     check
);

import "DPI-C" function void dromajo_cosim_raise_trap(
    input int     hartid,
    input longint cause
);

module DromajoCosimBlackbox
    #(parameter COMMIT_WIDTH, XLEN, __BOOTROM_NAME, RESET_VECTOR)
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
    string __binary_name, __bootrom_name, __dtb_name, __mmio_start, __mmio_end;
    int __itr, __fail;

    initial begin
        $display("[DEBUG] Setting up Dromajo Cosimulation");
        if ($value$plusargs ("drj_binary_name=%s", __binary_name)) begin
            $display("[DEBUG] __binary_name=%s", __binary_name);
        end
        if ($value$plusargs ("drj_dtb_name=%s", __dtb_name)) begin
            $display("[DEBUG] __dtb_name=%s", __dtb_name);
        end
        if ($value$plusargs ("drj_mmio_start=%s", __mmio_start)) begin
            $display("[DEBUG] __mmio_start=%s", __mmio_start);
        end
        if ($value$plusargs ("drj_mmio_end=%s", __mmio_end)) begin
            $display("[DEBUG] __mmio_end=%s", __mmio_end);
        end
        __fail = dromajo_init(
            __binary_name,
            __BOOTROM_NAME,
            RESET_VECTOR,
            __dtb_name,
            __mmio_start,
            __mmio_end);
        if (__fail) begin
            $display("FAIL: Dromajo Simulation Failed");
            $fatal;
        end

        $display("[DEBUG] Done setting up Dromajo Cosimulation");
    end

    always @(posedge clock) begin
        if (!reset) begin
            for (__itr=0; __itr<COMMIT_WIDTH; __itr=__itr+1) begin
                if (valid[__itr]) begin
                    __fail = dromajo_step(
                        hartid,
                        pc[((__itr+1)*XLEN - 1)-:XLEN],
                        inst[((__itr+1)*`INST_LEN - 1)-:`INST_LEN],
                        wdata[((__itr+1)*XLEN - 1)-:XLEN],
                        mstatus[((__itr+1)*XLEN - 1)-:XLEN],
                        check[__itr]);
                    if (__fail) begin
                        $display("FAIL: Dromajo Simulation Failed");
                        $fatal;
                    end
                end
            end

            if (int_xcpt) begin
                dromajo_cosim_raise_trap(
                    hartid,
                    cause
                );
            end
        end
    end

endmodule
