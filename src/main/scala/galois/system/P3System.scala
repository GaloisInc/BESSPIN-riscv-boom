//******************************************************************************
// Copyright (c) 2017 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
// Author: Christopher Celio
//------------------------------------------------------------------------------

// See LICENSE.SiFive for license details.

package boom.galois.system

import chisel3._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util.DontTouch
import boom.system._

/** Example Top with periphery devices and ports, and a Boom subsystem */
class P3System(implicit p: Parameters) extends BoomSubsystem
    with HasAsyncExtInterrupts
    with CanHaveMisalignedMasterAXI4MemPort
    with CanHaveMasterAXI4MMIOPort
    with CanHaveSlaveAXI4Port {
  override lazy val module = new P3SystemModule(this)

  // Error device used for testing and to NACK invalid front port transactions
  val error = LazyModule(new TLError(p(ErrorDeviceKey), sbus.beatBytes))
  // always buffer the error device because no one cares about its latency
  sbus.coupleTo("slave_named_error"){ error.node := TLBuffer() := _ }
}

/** Subsystem will power-on running at 0x7000_0000 (AXI Boot ROM) */
trait HasGaloisGFEResetVectorImp extends LazyModuleImp
  with HasResetVectorWire {
  global_reset_vector := 0x70000000L.U
}

class P3SystemModule[+L <: P3System](_outer: L) extends BoomSubsystemModule(_outer)
    with HasRTCModuleImp
    with HasExtInterruptsModuleImp
    with HasGaloisGFEResetVectorImp
    with CanHaveMisalignedMasterAXI4MemPortModuleImp
    with CanHaveMasterAXI4MMIOPortModuleImp
    with CanHaveSlaveAXI4PortModuleImp
    with DontTouch
