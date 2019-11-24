//******************************************************************************
// Copyright (c) 2019 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Dromajo Cosimulation Blackbox
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Takes commit signals from the core and runs the Dromajo cosimulation tool
// for functional verification.

package boom.util

import chisel3._
import chisel3.util._
import chisel3.core.{IntParam, StringParam}

/**
 * Connect to the Dromajo Cosimulation Tool
 */
class DromajoCosimBlackbox(commit_width: Int, xLen: Int, bootrom_name: String, reset_vector: String)
  extends BlackBox(Map(
    "COMMIT_WIDTH" -> IntParam(commit_width),
    "XLEN" -> IntParam(xLen),
    "BOOTROM_NAME" -> StringParam(bootrom_name),
    "RESET_VECTOR" -> StringParam(reset_vector)))
  with HasBlackBoxResource
{
  val inst_sz = 32
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val valid   = Input(UInt(        (commit_width).W))
    val hartid  = Input(UInt(                (xLen).W))
    val pc      = Input(UInt(   (xLen*commit_width).W))
    val inst    = Input(UInt((inst_sz*commit_width).W))
    val wdata   = Input(UInt(   (xLen*commit_width).W))
    val mstatus = Input(UInt(   (xLen*commit_width).W))
    val check   = Input(UInt(        (commit_width).W))

    val int_xcpt = Input(      Bool())
    val cause    = Input(UInt(xLen.W))
  })

  addResource("/vsrc/dromajo_cosim.v")
  addResource("/csrc/dromajo_cosim.cc")
  addResource("/csrc/dromajo.cc")
  addResource("/csrc/dromajo.h")
}
