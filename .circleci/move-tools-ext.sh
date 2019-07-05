#!/bin/bash

set -ex

WORK_DIR=/scratch/abejgonza/$CIRCLE_BRANCH-$CIRCLE_SHA1

SERVER=abe.gonzalez@a5.millennium.berkeley.edu

rsync -avz -e 'ssh' /home/riscvuser/riscv-tools-install $SERVER:$WORK_DIR/
