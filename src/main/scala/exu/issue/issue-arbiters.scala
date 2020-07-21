//******************************************************************************
// Copyright (c) 2020 - 2020, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Issue Arbiters for Ring Microarchitecture
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.Parameters

import boom.common._
import boom.util._


abstract class IssueArbiter(implicit p: Parameters) extends BoomModule
{
  val io = IO(new BoomBundle {
    val reqs = Input(Vec(coreWidth, Bool()))
    val uops = Input(Vec(coreWidth, new MicroOp))

    val gnts = Output(Vec(coreWidth, Bool()))

    val fire = Input(Vec(coreWidth, Bool()))

    val prs1_port = Output(Vec(coreWidth, UInt(numIrfReadPorts.W)))
    val prs2_port = Output(Vec(coreWidth, UInt(numIrfReadPorts.W)))
  })

  // Rotating priority
  val pri = RegInit(1.U(coreWidth.W))
  pri := RotateLeft(pri)

  def Grant(reqs: UInt) = {
    AgePriorityEncoderOH(reqs,pri)
  }

  val nacks = VecInit( io.reqs zip io.gnts map { case (r,g) => r && !g } )
  val num_nacks = RegInit(0.U(32.W))
  num_nacks := num_nacks + PopCount(nacks)
  dontTouch(num_nacks)

  io.prs1_port := DontCare
  io.prs2_port := DontCare
}

class RegisterReadArbiter(implicit p: Parameters) extends IssueArbiter
{
  val bank_reqs = Wire(Vec(coreWidth*2, UInt(coreWidth.W)))
  for (w <- 0 until coreWidth) {
    val uop = io.uops(w)
    bank_reqs(2*w  ) := Mux(io.reqs(w) && uop.prs1_reads_irf, uop.prs1_col, 0.U)
    bank_reqs(2*w+1) := Mux(io.reqs(w) && uop.prs2_reads_irf, uop.prs2_col, 0.U)
  }
  val n = numIrfReadPortsPerBank
  val port_pri  = (0 until coreWidth).map(w => Cat(0.U(1.W), pri(w))).reduce((l,u) => Cat(u,l))
  val port_gnts = Transpose(bank_reqs).map(r => AgeSelectFirstN(r, port_pri, n).toSeq).reduce(_++_)
  val port_sels = Transpose(port_gnts)
  val gnts      = port_gnts.reduce(_|_)

  val specifiers = io.uops.map(u => Seq(u.prs1, u.prs2)).reduce(_++_)
  val matches = Wire(Vec(2*coreWidth, Vec(2*coreWidth, Bool())))
  for (i <- 0 until coreWidth) {
    for (j <- 0 until coreWidth) {
      if (i < j) {
        matches(i)(j) := false.B
      } else if (i == j) {
        matches(i)(j) := true.B
      } else {
        matches(i)(j) := specifiers(i) === specifiers(j)
      }
    }
  }

  for (w <- 0 until coreWidth) {
    val prs1_matches = VecInit((0 until 2*coreWidth).map(i => if (i < 2*w  ) specifiers(2*w)(i) else specifiers(i)(2*w  ))).asUInt
    val prs2_matches = VecInit((0 until 2*coreWidth).map(i => if (i < 2*w+1) specifiers(2*w)(i) else specifiers(i)(2*w+1))).asUInt

    val prs1_gnts = gnts & prs1_matches
    val prs2_gnts = gnts & prs2_matches
    io.gnts(w) := (prs1_gnts.orR || !io.uops(w).prs1_reads_irf && io.reqs(w)) &&
                  (prs2_gnts.orR || !io.uops(w).prs2_reads_irf && io.reqs(w))
    io.prs1_port(w) := Mux1H(prs1_matches, port_sels)
    io.prs2_port(w) := Mux1H(prs2_matches, port_sels)
  }
}

class ExecutionArbiter(implicit p: Parameters) extends IssueArbiter
{
  val mem_reqs = VecInit((io.reqs zip io.uops) map { case (r,u) => r && u.eu_code(1) })
  val mem_gnts = AgeSelectFirstN(mem_reqs.asUInt, pri, memWidth)

  val unq_reqs = Transpose(VecInit((0 until coreWidth).map(w => io.uops(w).eu_code(3,2) & Fill(2, io.reqs(w)))))
  val unq_gnts = unq_reqs.map(r => Grant(r))

  val gnts = mem_gnts.reduce(_|_) | unq_gnts.reduce(_|_)

  for (w <- 0 until coreWidth) {
    io.gnts(w) := gnts(w) || !io.uops(w).shared_eu_code.orR && io.reqs(w)
  }
}

class WritebackArbiter(implicit p: Parameters) extends IssueArbiter
{
  val wb_table = Reg(Vec(coreWidth, UInt(maxSchedWbLat.W)))

  for (w <- 0 until coreWidth) {
    val latency = io.uops(w).exe_wb_latency

    io.gnts(w) := !(wb_table(w) & latency).orR && io.reqs(w)
    wb_table(w) := Mux(io.fire(w), wb_table(w) | latency, wb_table(w)) >> 1
  }
}

class ChainedWakeupArbiter(implicit p: Parameters) extends IssueArbiter
{
  val column_wakeup_reqs = Transpose(VecInit((0 until coreWidth).map(w => Mux(io.reqs(w), io.uops(w).column, 0.U))))
  val column_wakeup_gnts = Transpose(VecInit(column_wakeup_reqs.map(r => Grant(r))))

  for (w <- 0 until coreWidth) {
    io.gnts(w) := column_wakeup_gnts(w).orR
  }
}
