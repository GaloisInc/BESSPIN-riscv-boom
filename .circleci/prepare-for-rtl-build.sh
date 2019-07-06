#!/bin/bash

# build verilator and init submodules with rocket-chip hash given by riscv-boom

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

if [ ! -d "$HOME/chipyard" ]; then
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

    # make boom-template verilator version
    # set stricthostkeychecking to no (must happen before rsync)
    run "echo \"Ping $SERVER\""

    copy /home/riscvuser/chipyard $SERVER:$WORK_DIR/

    run "cd $HOME/chipyard/sims/verisim && make verilator_install"

    copy $SERVER:$WORK_DIR/chipyard /home/riscvuser/chipyard
fi

