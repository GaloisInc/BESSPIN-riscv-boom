#!/bin/bash

# init manager with chipyard, setup firesim

# turn echo on and error on earliest command
set -ex

# get shared variables
SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
source $SCRIPT_DIR/defaults.sh

# TODO: CLEAR OLD FIRESIM DIR
#run_script $LOCAL_CHECKOUT_DIR/.circleci/clean-old-files.sh $CI_DIR

# create a script to run
cat <<EOF >> $LOCAL_CHECKOUT_DIR/firesim-manager-setup.sh
#!/bin/bash

cd $REMOTE_AWS_WORK_DIR
git clone --progress --verbose https://github.com/ucb-bar/chipyard.git $REMOTE_AWS_CHIPYARD_DIR
cd $REMOTE_AWS_CHIPYARD_DIR
echo "Checking out Chipyard version: $(cat $LOCAL_CHECKOUT_DIR/CHIPYARD.hash)"
git fetch
git checkout $(cat $LOCAL_CHECKOUT_DIR/CHIPYARD.hash)
./scripts/init-submodules-no-riscv-tools.sh
./scripts/firesim-setup.sh --fast
cd $REMOTE_AWS_FSIM_DIR
source sourceme_f1_manager.sh
firesim managerinit <<EOD





EOD 
rm -rf $REMOTE_AWS_CHIPYARD_DIR/generators/boom
EOF

#         git checkout -C $REMOTE_AWS_MARSHAL_DIR MY_HASH_WITH_THE_CAT_PROC_STUFF"

chmod +x $LOCAL_CHECKOUT_DIR/firesim-manager-setup.sh
run_script_aws $LOCAL_CHECKOUT_DIR/firesim-manager-setup.sh

copy $LOCAL_CHECKOUT_DIR $AWS_SERVER:$REMOTE_AWS_CHIPYARD_DIR/generators/boom/
