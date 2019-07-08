#!/bin/bash

# build verilator and init submodules with rocket-chip hash given by riscv-boom

# turn echo on and error on earliest command
set -ex

# get remote exec variables
SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
source $SCRIPT_DIR/defaults.sh

# check to see if both dirs exist
if [ ! -d "$LOCAL_VERILATOR_DIR" ] && [ ! -d "$LOCAL_CHIPYARD_DIR" ]; then
    cd $HOME

    git clone --progress --verbose https://github.com/ucb-bar/project-template.git chipyard
    cd $LOCAL_CHIPYARD_DIR

    echo "Checking out Chipyard version: $(cat $LOCAL_CHECKOUT_DIR/CHIPYARD.hash)"
    git fetch
    git checkout $(cat $LOCAL_CHECKOUT_DIR/CHIPYARD.hash)

    # init all submodules (according to what boom-template wants)
    ./scripts/init-submodules-no-riscv-tools.sh

    # move the pull request riscv-boom repo into boom-template
    rm -rf $LOCAL_CHIPYARD_DIR/generators/boom
    cp -r $LOCAL_CHECKOUT_DIR $LOCAL_CHIPYARD_DIR/generators/boom/

    # set stricthostkeychecking to no (must happen before rsync)
    run "echo \"Ping $SERVER\""

    copy $LOCAL_CHIPYARD_DIR/ $SERVER:$REMOTE_CHIPYARD_DIR

    run "make -C $REMOTE_CHIPYARD_DIR/sims/verisim VERILATOR_INSTALL_DIR=$REMOTE_VERILATOR_DIR verilator_install"

    # copy so that circleci can cache
    copy $SERVER:$REMOTE_CHIPYARD_DIR/  $LOCAL_CHIPYARD_DIR
    copy $SERVER:$REMOTE_VERILATOR_DIR/ $LOCAL_VERILATOR_DIR

    # remove local copies
    run "rm -rf $REMOTE_CHIPYARD_DIR"
    run "rm -rf $REMOTE_VERILATOR_DIR"
fi

