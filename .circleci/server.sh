#!/bin/bash

WORK_DIR=$CI_DIR/$CIRCLE_BRANCH-$CIRCLE_SHA1

copy () {
    rsync -avz -e 'ssh' $1 $2
}

run () {
    ssh -o "StrictHostKeyChecking no" -t $SERVER $1
}
