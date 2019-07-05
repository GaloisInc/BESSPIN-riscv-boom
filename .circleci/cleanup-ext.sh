#!/bin/bash

set -ex

WORK_DIR=/scratch/abejgonza/$CIRCLE_BRANCH-$CIRCLE_SHA1
SERVER=abe.gonzalez@a5.millennium.berkeley.edu
RUN=ssh -o "StrictHostKeyChecking no" -t $SERVER

$RUN "rm -rf $WORK_DIR"