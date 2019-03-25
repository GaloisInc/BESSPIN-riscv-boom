//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
// Author: Christopher Celio
//------------------------------------------------------------------------------

package boom.galois.system

import chisel3._
import freechips.rocketchip.config.{Parameters, Config}
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.system._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import boom.common._
import boom.system.WithNBoomCores
import boom.system._

// scalastyle:off

class BoomP3FPGAConfig extends Config(
   new WithRVC ++
   new WithSmallBooms ++
   new DefaultBoomConfig ++
   new WithNBoomCores(1) ++
   new WithoutTLMonitors ++
   new freechips.rocketchip.system.BaseConfig ++
   new WithJtagDTM)


