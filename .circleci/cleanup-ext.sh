#!/bin/bash

set -ex

# get shared variables
SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
source $SCRIPT_DIR/defaults.sh

run "rm -rf $REMOTE_WORK_DIR"
