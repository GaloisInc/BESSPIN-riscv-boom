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
import freechips.rocketchip.galois.devices._

// scalastyle:off

class WithXilinxJtag extends Config ((site, here, up) => {
  // Xilinx requires an IR length of 18, special register addresses, and latching TDO on positive edge
  case JtagDTMKey => new JtagDTMConfig(
    idcodeVersion = 0, idcodePartNum = 0, idcodeManufId = 0, debugIdleCycles = 5,
    irLength = 18, tdoOnNegEdge = false, registerAddrs = new xilinxAddrs()
  )
})

class BoomP3FPGAConfig extends Config(
   new WithXilinxJtag ++
   new WithRVC ++
   new WithGFECLINT ++
   new DefaultBoomConfig ++
   new WithNBoomCores(1) ++
   new WithoutTLMonitors ++
   new WithNExtTopInterrupts(16) ++
   new WithJtagDTM ++
   new freechips.rocketchip.system.BaseConfig
   )


