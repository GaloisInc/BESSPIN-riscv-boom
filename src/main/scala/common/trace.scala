
package bluespec.trace

import chisel3._
import chisel3.util._
import chisel3.experimental._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy.LazyModuleImp
import freechips.rocketchip.tile._
import freechips.rocketchip.util._
import freechips.rocketchip.rocket.CSR
import scala.math.max
import chisel3.experimental.dontTouch

import boom.common._
import boom.util.{Compactor, Sext}

object TraceConstants {
  val OP_BEGIN = 1
  val OP_END = 2
  val OP_INCPC = 3
  val OP_REG = 4
  val OP_REGINCR = 5
  val OP_REGOR = 6
  val OP_ASTATE = 7
  val OP_MEMREQ = 8
  val OP_MEMRSP = 9
  val OP_RESET = 10
  val OP_INIT = 11
  val OP_INST16 = 16
  val OP_INST32 = 17

  val ASTATE_PRIV = 1
  val ASTATE_PADDR = 2
  val ASTATE_EADDR = 3
  val ASTATE_MEMDATA8 = 4
  val ASTATE_MEMDATA16 = 5
  val ASTATE_MEMDATA32 = 6
  val ASTATE_MEMDATA64 = 7
  val ASTATE_MTIME = 8
  val ASTATE_PCPADDR = 9
  val ASTATE_PC = 10
  val ASTATE_DEBUG = 11

  val CSR_FFLAGS = 0x001

  val CSR_SSTATUS = 0x100
  val CSR_SEPC = 0x141
  val CSR_SCAUSE = 0x142
  val CSR_STVAL = 0x143

  val CSR_MSTATUS = 0x300
  val CSR_MEPC = 0x341
  val CSR_MCAUSE = 0x342
  val CSR_MTVAL = 0x343
}
import TraceConstants._

/// \todo replace with axis port (tdata, strb, etc.)
class TraceVector extends Bundle {
  val vec = Vec(72, UInt(8.W))
  val count = UInt(7.W)
}

class TraceEncoderInput(implicit p: Parameters) extends CoreBundle()(p) {
  val hartreset = Bool()

  val pc = Valid(UInt(xLen.W))

  val instr = Valid(new Bundle {
    val instr = UInt(32.W)
    val rvc = Bool()
  })

  val gpr = Valid(new Bundle {
    val idx = UInt(5.W)
    val data = UInt(xLen.W)
  })

  val fpr = Valid(new Bundle {
    val idx = UInt(5.W)
    val data = UInt(fLen.W)
  })

  val csr = Valid(new Bundle {
    val idx = UInt(12.W)
    val data = UInt(xLen.W)
  })

  val epc = Valid(UInt(xLen.W))
  val cause = Valid(UInt(xLen.W))
  val status = Valid(UInt(xLen.W))
  val tval = Valid(UInt(xLen.W))
  val priv = Valid(UInt(xLen.W))
  val paddr = Valid(UInt(max(xLen, maxPAddrBits).W))
  val eaddr = Valid(UInt(max(xLen, maxPAddrBits).W))

  val memdata = Valid(new Bundle {
    val data = UInt(xLen.W)
    val size = UInt(2.W)  // log2(size)-1
  })

  val fflags = Valid(UInt(5.W))
  val debug = Valid(Bool())
}

class TracePreEncoderIO(implicit p: Parameters) extends Bundle {
  val trace_output = Decoupled(new TraceEncoderInput)

  override def toPrintable: Printable = {
    p"trace_output: $trace_output\n"
  }
}

class TracePreEncoder(implicit p: Parameters) extends Module() {
  // set to true to omit trace during debug
  val filter_dbgmode = true.B

  val io = IO(new TracePreEncoderIO())

  val q = Module(new Queue(new TraceEncoderInput(), 1, true))

  val hartreset = RegNext(true.B)

  val commit_valid = WireDefault(false.B)
  val commit_inst = WireDefault(UInt(32.W), 0.U)
  val commit_is_rvc = WireDefault(false.B)

  val commit_wdata = WireDefault(UInt(p(XLen).W), 0.U)

  val commit_fflags_valid = WireDefault(false.B)
  val commit_fflags = WireDefault(UInt(5.W), 0.U)

  val commit_ldst = WireDefault(UInt(64.W), 0.U)
  val commit_dst_rtype = WireDefault(UInt(2.W), 0.U)

  val csr_wdata_p = WireDefault(UInt(p(XLen).W), 0.U)
  val csr_addr_p = WireDefault(UInt(CSR.ADDRSZ.W), 0.U)
  val csr_wen_p = WireDefault(false.B)
  val csr_eret_p = WireDefault(false.B)
  val csr_evec_p = WireDefault(UInt(p(XLen).W), 0.U)

  val csr_wdata = csr_wdata_p
  var csr_addr = csr_addr_p
  val csr_wen = RegNext(csr_wen_p)
  val csr_eret = RegNext(csr_eret_p)
  val csr_evec = RegNext(csr_evec_p)

  val epc_p = WireDefault(UInt(p(XLen).W), 0.U)
  val epc = RegNext(epc_p)

  val exception_p = WireDefault(false.B)
  val cause_p = WireDefault(UInt(p(XLen).W), 0.U)

  val exception = RegNext(exception_p)
  val cause = RegNext(cause_p)

  val status_p = WireDefault(UInt(p(XLen).W), 0.U)
  val status = status_p

  val tval_p = WireDefault(UInt(p(XLen).W), 0.U)
  val tval = RegNext(tval_p)

  val tval_valid_p = WireDefault(false.B)
  val tval_valid = RegNext(tval_valid_p)

  val priv_p = WireDefault(UInt(3.W), 0.U)
  val priv = priv_p

  val is_br_or_jmp = WireDefault(false.B)
  val taken = WireDefault(false.B)
  val bj_addr = WireDefault(UInt(p(XLen).W), 0.U)

  val debug = (priv & 4.U) =/= 0.U
  val debug_d = RegNext(debug)

  print(p"$io")

  when (hartreset && q.io.enq.valid && q.io.enq.ready) {
    hartreset := false.B
  }
  .otherwise {
    hartreset := hartreset
  }

  q.io.enq.valid := (commit_valid || exception) && (!filter_dbgmode || (!debug && !debug_d))
  q.io.enq.bits.hartreset := hartreset
  q.io.enq.bits.pc.valid := hartreset || exception || csr_eret || (is_br_or_jmp && taken)
  q.io.enq.bits.pc.bits := Mux(hartreset, 0x70000000L.U, Mux(exception || csr_eret, csr_evec, bj_addr))
  q.io.enq.bits.instr.valid := commit_valid
  q.io.enq.bits.instr.bits.instr := commit_inst
  q.io.enq.bits.instr.bits.rvc := commit_is_rvc
  q.io.enq.bits.gpr.valid := commit_dst_rtype === RT_FIX && commit_ldst =/= 0.U && !exception
  q.io.enq.bits.gpr.bits.idx := commit_ldst(4, 0)
  q.io.enq.bits.gpr.bits.data := commit_wdata
  q.io.enq.bits.fpr.valid := commit_dst_rtype === RT_FLT && !exception
  q.io.enq.bits.fpr.bits.idx := commit_ldst(4, 0)
  q.io.enq.bits.fpr.bits.data := commit_wdata
  q.io.enq.bits.csr.valid := csr_wen
  q.io.enq.bits.csr.bits.idx := csr_addr
  q.io.enq.bits.csr.bits.data := csr_wdata
  q.io.enq.bits.epc.valid := exception
  q.io.enq.bits.epc.bits := epc
  q.io.enq.bits.cause.valid := exception
  q.io.enq.bits.cause.bits := cause
  q.io.enq.bits.status.valid := exception || csr_eret
  q.io.enq.bits.status.bits := status
  q.io.enq.bits.tval.valid := exception
  q.io.enq.bits.tval.bits := tval
  q.io.enq.bits.priv.valid := hartreset || exception || csr_eret
  q.io.enq.bits.priv.bits := Mux(hartreset, 3.U, priv & 3.U)
  q.io.enq.bits.paddr.valid := 0.U
  q.io.enq.bits.paddr.bits := 0.U
  q.io.enq.bits.eaddr.valid := 0.U
  q.io.enq.bits.eaddr.bits := 0.U
  q.io.enq.bits.memdata.valid := 0.U
  q.io.enq.bits.memdata.bits.data := 0.U
  q.io.enq.bits.memdata.bits.size := 0.U
  q.io.enq.bits.fflags.valid := commit_fflags_valid
  q.io.enq.bits.fflags.bits := commit_fflags
  q.io.enq.bits.debug.valid := (debug =/= debug_d) && !filter_dbgmode
  q.io.enq.bits.debug.bits := debug

  io.trace_output <> q.io.deq

  dontTouch(io.trace_output)
}

class TraceEncoderIO()(implicit p: Parameters) extends Bundle {
  val input = Flipped(Decoupled(new TraceEncoderInput))
  val output = Decoupled(new TraceVector)
  val stall = Input(Bool())

  override def toPrintable: Printable = {
    p"input : $input\n" +
    p"output: $output\n"
  }
}

class TraceEncoder(implicit p: Parameters) extends Module() {
  val io = IO(new TraceEncoderIO())

  val v = Wire(Vec(135, Valid(UInt(8.W))))
  val compactor = Module(new Compactor(v.length, io.output.bits.vec.length, UInt(8.W)))
  val q1 = Module(new Queue(Vec(v.length, Valid(UInt(8.W))), 1, true))
  val q2 = Module(new Queue(Vec(io.output.bits.vec.length, Valid(UInt(8.W))), 1, true))

  def toVec(in: UInt, len: Int) : Vec[UInt] = {
    val out = Wire(Vec(len, UInt(8.W)))
    out := (if (len > 0) in.padTo(64)(7, 0) +: toVec(in >> 8, len-1) else Nil)
    out
  }

  def setBitsValid[T <: Data](a: Valid[T], b: T, c: Bool) = {
    a.bits := b
    a.valid := c
  }

  def setBits[T <: Data](a: Valid[T], b: T) = setBitsValid(a, b, true.B)
  def setInvalid[T <: Data](a: Valid[T]) = setBitsValid(a, DontCare, false.B)

  val epc_idx = Mux((io.input.bits.priv.valid === true.B) &&
		    (io.input.bits.priv.bits === 1.U),
		    TraceConstants.CSR_SEPC.asUInt,
		    TraceConstants.CSR_MEPC.asUInt)

  val cause_idx = Mux((io.input.bits.priv.valid === true.B) &&
		    (io.input.bits.priv.bits === 1.U),
		    TraceConstants.CSR_SCAUSE.asUInt,
		    TraceConstants.CSR_MCAUSE.asUInt)

  val tval_idx = Mux((io.input.bits.priv.valid === true.B) &&
		    (io.input.bits.priv.bits === 1.U),
		    TraceConstants.CSR_STVAL.asUInt,
		    TraceConstants.CSR_MTVAL.asUInt)

  print(p"$io")

  io.input.ready := q1.io.enq.ready
  q1.io.enq.valid := io.input.valid

  v.slice(0, 1) zip {
    toVec(TraceConstants.OP_BEGIN.asUInt, 1)
  } foreach {case (a,b) => setBits(a,b)}

  when (io.input.bits.hartreset) {
    v.slice(134, 135) zip {
      toVec(TraceConstants.OP_RESET.asUInt, 1)
    } foreach {case (a,b) => setBits(a,b)}
  }
  .otherwise {
    v.slice(134, 135) foreach {setInvalid(_)}
  }

  when (io.input.bits.pc.valid === true.B) {
    v.slice(1, 11) zip {
      toVec(TraceConstants.OP_ASTATE.asUInt, 1) ++
      toVec(TraceConstants.ASTATE_PC.asUInt, 1) ++
      toVec(io.input.bits.pc.bits(39, 0).sextTo(p(XLen)), 8)
    } foreach {case (a,b) => setBits(a,b)}
  }
  .otherwise {
    v.slice(1, 2) zip {
      toVec(TraceConstants.OP_INCPC.asUInt, 1)
    } foreach {case (a,b) => setBits(a,b)}
    v.slice(2, 11) foreach {setInvalid(_)}
  }

  when (io.input.bits.instr.valid === true.B) {
    when (io.input.bits.instr.bits.rvc === true.B) {
      v.slice(11, 14) zip {
	toVec(TraceConstants.OP_INST16.asUInt, 1) ++
	toVec(io.input.bits.instr.bits.instr, 2)
      } foreach {case (a,b) => setBits(a,b)}
      v.slice(14, 16) foreach {setInvalid(_)}
    }
    .otherwise {
      v.slice(11, 16) zip {
	toVec(TraceConstants.OP_INST32.asUInt, 1) ++
	toVec(io.input.bits.instr.bits.instr, 4)
      } foreach {case (a,b) => setBits(a,b)}
    }
  }
  .otherwise {
    v.slice(11, 16) foreach {setInvalid(_)}
  }

  v.slice(16, 27) zip {
    toVec(TraceConstants.OP_REG.asUInt, 1) ++
    toVec(io.input.bits.gpr.bits.idx.asUInt + 0x1000.U, 2) ++
    toVec(io.input.bits.gpr.bits.data, 8)
  } foreach {case (a,b) =>
    setBitsValid(a, b, (io.input.bits.gpr.valid === true.B))}

  v.slice(27, 38) zip {
    toVec(TraceConstants.OP_REG.asUInt, 1) ++
    toVec(io.input.bits.fpr.bits.idx.asUInt + 0x1020.U, 2) ++
    toVec(io.input.bits.fpr.bits.data, 8)
  } foreach {case (a,b) =>
    setBitsValid(a, b, (io.input.bits.fpr.valid === true.B))}

  v.slice(38, 49) zip {
    toVec(TraceConstants.OP_REG.asUInt, 1) ++
    toVec(io.input.bits.csr.bits.idx.asUInt, 2) ++
    toVec(io.input.bits.csr.bits.data, 8)
  } foreach {case (a,b) =>
    setBitsValid(a, b, (io.input.bits.csr.valid === true.B))}

  v.slice(49, 60) zip {
    toVec(TraceConstants.OP_REG.asUInt, 1) ++
    toVec(TraceConstants.CSR_MSTATUS.asUInt, 2) ++
    toVec(io.input.bits.status.bits, 8)
  } foreach {case (a,b) =>
    setBitsValid(a, b, (io.input.bits.status.valid === true.B))}

  v.slice(60, 71) zip {
    toVec(TraceConstants.OP_REG.asUInt, 1) ++
    toVec(epc_idx, 2) ++
    toVec(io.input.bits.epc.bits, 8)
  } foreach {case (a,b) =>
    setBitsValid(a, b, (io.input.bits.epc.valid === true.B))}

  v.slice(71, 82) zip {
    toVec(TraceConstants.OP_REG.asUInt, 1) ++
    toVec(cause_idx, 2) ++
    toVec(io.input.bits.cause.bits, 8)
  } foreach {case (a,b) =>
    setBitsValid(a, b, (io.input.bits.cause.valid === true.B))}

  v.slice(82, 93) zip {
    toVec(TraceConstants.OP_REG.asUInt, 1) ++
    toVec(tval_idx, 2) ++
    toVec(io.input.bits.tval.bits, 8)
  } foreach {case (a,b) =>
    setBitsValid(a, b, (io.input.bits.tval.valid === true.B))}

  v.slice(93, 96) zip {
    toVec(TraceConstants.OP_ASTATE.asUInt, 1) ++
    toVec(TraceConstants.ASTATE_PRIV.asUInt, 1) ++
    toVec(io.input.bits.priv.bits, 1)
  } foreach {case (a,b) =>
    setBitsValid(a, b, (io.input.bits.priv.valid === true.B))}

  v.slice(96, 106) zip {
    toVec(TraceConstants.OP_ASTATE.asUInt, 1) ++
    toVec(TraceConstants.ASTATE_PADDR.asUInt, 1) ++
    toVec(io.input.bits.paddr.bits, 8)
  } foreach {case (a,b) =>
    setBitsValid(a, b, (io.input.bits.paddr.valid === true.B))}

  v.slice(106, 116) zip {
    toVec(TraceConstants.OP_ASTATE.asUInt, 1) ++
    toVec(TraceConstants.ASTATE_EADDR.asUInt, 1) ++
    toVec(io.input.bits.eaddr.bits, 8)
  } foreach {case (a,b) =>
    setBitsValid(a, b, (io.input.bits.eaddr.valid === true.B))}

  when (io.input.bits.memdata.valid === true.B) {
    when (io.input.bits.memdata.bits.size === 0.U) {
      v.slice(116, 119) zip {
	toVec(TraceConstants.OP_ASTATE.asUInt, 1) ++
	toVec(TraceConstants.ASTATE_MEMDATA8.asUInt, 1) ++
	toVec(io.input.bits.memdata.bits.data, 1)
      } foreach {case (a,b) => setBits(a,b)}
      v.slice(119, 126) foreach {setInvalid(_)}
    }
    .elsewhen (io.input.bits.memdata.bits.size === 1.U) {
      v.slice(116, 120) zip {
	toVec(TraceConstants.OP_ASTATE.asUInt, 1) ++
	toVec(TraceConstants.ASTATE_MEMDATA16.asUInt, 1) ++
	toVec(io.input.bits.memdata.bits.data, 2)
      } foreach {case (a,b) => setBits(a,b)}
      v.slice(120, 126) foreach {setInvalid(_)}
    }
    .elsewhen (io.input.bits.memdata.bits.size === 2.U) {
      v.slice(116, 122) zip {
	toVec(TraceConstants.OP_ASTATE.asUInt, 1) ++
	toVec(TraceConstants.ASTATE_MEMDATA32.asUInt, 1) ++
	toVec(io.input.bits.memdata.bits.data, 4)
      } foreach {case (a,b) => setBits(a,b)}
      v.slice(122, 126) foreach {setInvalid(_)}
    }
    .elsewhen (io.input.bits.memdata.bits.size === 3.U) {
      v.slice(116, 126) zip {
	toVec(TraceConstants.OP_ASTATE.asUInt, 1) ++
	toVec(TraceConstants.ASTATE_MEMDATA64.asUInt, 1) ++
	toVec(io.input.bits.memdata.bits.data, 8)
      } foreach {case (a,b) => setBits(a,b)}
    }
    .otherwise {
      v.slice(116, 126) foreach {setInvalid(_)}
    }
  }
  .otherwise {
    v.slice(116, 126) foreach {setInvalid(_)}
  }

  v.slice(126, 130) zip {
    toVec(TraceConstants.OP_REGOR.asUInt, 1) ++
    toVec(TraceConstants.CSR_FFLAGS.asUInt, 2) ++
    toVec(io.input.bits.fflags.bits.asUInt, 1)
  } foreach {case (a,b) =>
    setBitsValid(a, b, (io.input.bits.fflags.valid === true.B))}

  v.slice(130, 133) zip {
    toVec(TraceConstants.OP_ASTATE.asUInt, 1) ++
    toVec(TraceConstants.ASTATE_DEBUG.asUInt, 1) ++
    toVec(io.input.bits.debug.bits, 1)
  } foreach {case (a,b) =>
    setBitsValid(a, b, (io.input.bits.debug.valid === true.B))}

  v.slice(133, 134) zip {
    toVec(TraceConstants.OP_END.asUInt, 1)
  } foreach {case (a,b) => setBits(a,b)}

  q1.io.enq.bits <> v

  q1.io.deq.ready := q2.io.enq.ready
  q2.io.enq.valid := q1.io.deq.valid

  compactor.io.in zip q1.io.deq.bits foreach {case (a,b) =>
      a.valid := b.valid
      a.bits := b.bits
    }

  q2.io.enq.bits zip compactor.io.out foreach {case (a,b) =>
      a.valid := b.valid
      a.bits := b.bits
      b.ready := q2.io.enq.ready
    }

  q2.io.deq.ready := io.output.ready
  io.output.valid := q2.io.deq.valid

  io.output.bits.count := PopCount(q2.io.deq.bits map {_.valid})
  io.output.bits.vec zip q2.io.deq.bits foreach {case (a,b) => a := b.bits }
}
