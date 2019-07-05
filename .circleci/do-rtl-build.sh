#!/bin/bash

# create the different verilator builds of BOOM based on arg

# turn echo on and error on earliest command
set -ex

WORK_DIR=/scratch/abejgonza/$CIRCLE_BRANCH-$CIRCLE_SHA1
SERVER=abe.gonzalez@a5.millennium.berkeley.edu
RUN=ssh -o "StrictHostKeyChecking no" -t $SERVER
RSYNC=rsync -avz -e 'ssh -o StrictHostKeyChecking no'

$RSYNC /home/riscvuser/chipyard $SERVER:$WORK_DIR/$1/

# enter the verisim directory and build the specific config on remote server
$RUN "make -C $WORK_DIR/$1/chipyard/sims/verisim RISCV=$WORK_DIR/riscv-tools-install clean"
$RUN "make -C $WORK_DIR/$1/chipyard/sims/verisim RISCV=$WORK_DIR/riscv-tools-install SUB_PROJECT=boom CONFIG=$1 TOP=BoomRocketSystem JAVA_ARGS=\"-Xmx2G -Xss8M\""

# copy back the final build
$RSYNC $SERVER:$WORK_DIR/$1/chipyard /home/riscvuser/chipyard
