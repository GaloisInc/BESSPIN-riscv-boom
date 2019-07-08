#!/bin/bash

# create the different verilator builds of BOOM based on arg

# turn echo on and error on earliest command
set -ex

# get remote exec variables
SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
source $SCRIPT_DIR/server.sh

JOB_DIR=$WORK_DIR/chipyard-$1
SIM_DIR=$JOB_DIR/chipyard/sims/verisim

# set stricthostkeychecking to no (must happen before rsync)
run "echo \"Ping $SERVER\""

run "mkdir -p $JOB_DIR"
run "cp -R $WORK_DIR/chipyard/ $JOB_DIR"

# enter the verisim directory and build the specific config on remote server
run "make -C $SIM_DIR clean"
run "export RISCV=\"$WORK_DIR/riscv-tools-install\"; echo \"$RISCV\"; make -C $SIM_DIR VERILATOR_INSTALL_DIR=$WORK_DIR/verilator JAVA_ARGS=\"-Xmx8G -Xss8M\" SUB_PROJECT=boom CONFIG=$1 TOP=BoomRocketSystem"

# copy back the final build
copy $SERVER:$JOB_DIR/chipyard $HOME/chipyard
