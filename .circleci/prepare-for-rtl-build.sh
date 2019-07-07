#!/bin/bash

# build verilator and init submodules with rocket-chip hash given by riscv-boom

# turn echo on and error on earliest command
set -ex

# get remote exec variables
SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
source $SCRIPT_DIR/server.sh

# check to see if both dirs exist
if [ ! -d "$HOME/verilator" ] && [ ! -d "$HOME/chipyard" ]; then
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

    copy $HOME/chipyard $SERVER:$WORK_DIR/

    run "make -C $WORK_DIR/chipyard/sims/verisim VERILATOR_INSTALL_DIR=$WORK_DIR/verilator verilator_install"

    # copy so that circleci can cache
    copy $SERVER:$WORK_DIR/chipyard  $HOME/chipyard
    copy $SERVER:$WORK_DIR/verilator $HOME/verilator

    # remove local copies
    run "rm -rf $WORK_DIR/chipyard"
    run "rm -rf $WORK_DIR/verilator"
fi

