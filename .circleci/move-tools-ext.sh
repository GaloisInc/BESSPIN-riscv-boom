#!/bin/bash

set -ex

# get remote exec variables
SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
source $SCRIPT_DIR/server.sh

# set stricthostkeychecking to no (must happen before rsync)
run "echo \"Ping $SERVER\""

copy /home/riscvuser/riscv-tools-install $SERVER:$WORK_DIR/
