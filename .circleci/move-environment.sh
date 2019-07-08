#!/bin/bash

set -ex

# get remote exec variables
SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
source $SCRIPT_DIR/defaults.sh

# set stricthostkeychecking to no (must happen before rsync)
run "echo \"Ping $SERVER\""

copy $LOCAL_RISCV_DIR/ $SERVER:$REMOTE_RISCV_DIR
copy $LOCAL_CHIPYARD_DIR/ $SERVER:$REMOTE_CHIPYARD_DIR
copy $LOCAL_VERILATOR_DIR/ $SERVER:$REMOTE_VERILATOR_DIR
