#!/bin/bash

# build verilator and init submodules with rocket-chip hash given by riscv-boom

# get remote exec variables
SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
source $SCRIPT_DIR/server.sh

# make boom-template verilator version
# set stricthostkeychecking to no (must happen before rsync)
run "echo \"Ping $SERVER\""

# does chipyard exist on server
run "[ -d $WORK_DIR/chipyard ]"
ret_code = $?

# turn echo on and error on earliest command
set -ex

if [ $ret_code == 1 ]; then
    cd $HOME

    git clone --progress --verbose https://github.com/ucb-bar/project-template.git chipyard
    cd $HOME/chipyard

    echo "Checking out Chipyard version: $(cat $HOME/project/CHIPYARD.hash)"
    git fetch
    git checkout $(cat $HOME/project/CHIPYARD.hash)

    # init all submodules (according to what boom-template wants)
    ./scripts/init-submodules-no-riscv-tools.sh

    # move the pull request riscv-boom repo into boom-template
    rm -rf $HOME/chipyard/generators/boom
    cp -r $HOME/project $HOME/chipyard/generators/boom/

    copy /home/riscvuser/chipyard $SERVER:$WORK_DIR/

    run "make -C $WORK_DIR/chipyard/sims/verisim VERILATOR_INSTALL_DIR=$WORK_DIR/verilator verilator_install"

    copy $SERVER:$WORK_DIR/chipyard /home/riscvuser/chipyard
fi

