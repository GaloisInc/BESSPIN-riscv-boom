#!/bin/bash

#SERVER=abe.gonzalez@a5.millennium.berkeley.edu
#CI_DIR=/vm/scratch/circleci
WORK_DIR=$CI_DIR/$CIRCLE_BRANCH-$CIRCLE_SHA1

copy () {
    rsync -avz -e 'ssh' $1 $2
}

run () {
    ssh -o "StrictHostKeyChecking no" -t $SERVER $1
}
