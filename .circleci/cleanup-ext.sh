#!/bin/bash

set -ex

SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
source $SCRIPT_DIR/server.sh

run "rm -rf $WORK_DIR"
