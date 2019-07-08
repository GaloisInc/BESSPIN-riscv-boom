#!/bin/bash

# test the verilator simulation using csmith random testing

# turn echo on and error on earliest command
set -ex

SIM_BASE=simulator-boom.system-
CONFIG=$1
SIM=${SIM_BASE}${CONFIG}
AMT_RUNS=$2

# run csmith utility
cd $LOCAL_CHECKOUT_DIR/util/csmith
./install-csmith.sh
./run-csmith.sh --sim $LOCAL_SIM_DIR/$SIM --run $AMT_RUNS --nodebug
