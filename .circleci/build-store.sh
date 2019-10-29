#!/bin/bash

# key value store to get the build strings
declare -A mapping
mapping["smallboom"]="CONFIG=SmallBoomConfig"
mapping["mediumboom"]="CONFIG=MediumBoomConfig"
mapping["largeboom"]="CONFIG=LargeBoomConfig"
mapping["megaboom"]="CONFIG=MegaBoomConfig"
mapping["boomandrocket"]="CONFIG=SmallBoomAndRocketConfig"
mapping["rv32boom"]="CONFIG=SmallRV32BoomConfig"
mapping["hwachaboom"]="CONFIG=HwachaLargeBoomConfig"
