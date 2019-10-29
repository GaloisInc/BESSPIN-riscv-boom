#!/bin/bash

# turn echo on and error on earliest command
set -ex

# get shared variables
SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
source $SCRIPT_DIR/defaults.sh

run_aws "cd $REMOTE_AWS_MARSHAL_DIR \
         ./marshal build test/fed-test.json \
         ./marshal install test/fed-test.json"
