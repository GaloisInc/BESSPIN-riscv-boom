#!/bin/bash

WORK_DIR=/scratch/abejgonza/$CIRCLE_BRANCH-$CIRCLE_SHA1
SERVER=abe.gonzalez@a5.millennium.berkeley.edu

ssh -o "StrictHostKeyChecking no" $SERVER "rm -rf $WORK_DIR"
