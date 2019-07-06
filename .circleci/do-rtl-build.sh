#!/bin/bash

# create the different verilator builds of BOOM based on arg

# turn echo on and error on earliest command
set -ex

WORK_DIR=/scratch/abejgonza/$CIRCLE_BRANCH-$CIRCLE_SHA1
SERVER=abe.gonzalez@a5.millennium.berkeley.edu

copy () {
    rsync -avz -e 'ssh' $1 $2
}

run () {
    ssh -o "StrictHostKeyChecking no" -t $SERVER $1
}

# set stricthostkeychecking to no (must happen before rsync)
run "echo \"Ping $SERVER\""

copy /home/riscvuser/chipyard $SERVER:$WORK_DIR/$1/

# enter the verisim directory and build the specific config on remote server
run "make -C $WORK_DIR/$1/chipyard/sims/verisim RISCV=$WORK_DIR/riscv-tools-install clean"
run "make -C $WORK_DIR/$1/chipyard/sims/verisim RISCV=$WORK_DIR/riscv-tools-install SUB_PROJECT=boom CONFIG=$1 TOP=BoomRocketSystem JAVA_ARGS=\"-Xmx8G -Xss8M\" VERILATOR_ROOT=$WORK_DIR/$1/chipyard/sims/verisim/verilator/install/share/verilator"

# copy back the final build
copy $SERVER:$WORK_DIR/$1/chipyard /home/riscvuser/chipyard
