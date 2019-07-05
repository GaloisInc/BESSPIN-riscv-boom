#!/bin/bash

set -ex

WORK_DIR=/scratch/abejgonza/$CIRCLE_BRANCH-$CIRCLE_SHA1
SERVER=abe.gonzalez@a5.millennium.berkeley.edu

copy () {
    rsync -avz -e 'ssh -o StrictHostKeyChecking no' $1 $2
}

run () {
    ssh -o "StrictHostKeyChecking no" -t $SERVER $1
}

run "rm -rf $WORK_DIR"
