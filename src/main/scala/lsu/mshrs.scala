//******************************************************************************
// Ported from Rocket-Chip
// See LICENSE.Berkeley and LICENSE.SiFive in Rocket-Chip for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.lsu

import chisel3._
import chisel3.util._
import chisel3.experimental.dontTouch

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tile._
import freechips.rocketchip.util._
import freechips.rocketchip.rocket._

import boom.common._
import boom.exu.BrResolutionInfo
import boom.util.{IsKilledByBranch, GetNewBrMask, BranchKillableQueue, IsOlder, UpdateBrMask, AgePriorityEncoder, WrapInc}

class BoomDCacheReqInternal(implicit p: Parameters) extends BoomDCacheReq()(p)
  with HasL1HellaCacheParameters
{
  // miss info
  val tag_match = Bool()
  val old_meta  = new L1Metadata
  val way_en    = UInt(nWays.W)

  // Used in the MSHRs
  val sdq_id    = UInt(log2Ceil(cfg.nSDQ).W)
  val lb_id     = UInt(log2Ceil(nLBEntries).W)
  val lb_coh    = new ClientMetadata
  val from_lb   = Bool()
}


class BoomMSHR(id: Int)(implicit edge: TLEdgeOut, p: Parameters) extends BoomModule()(p)
  with HasL1HellaCacheParameters
{
  val io = IO(new Bundle {
    val req_pri_val = Input(Bool())
    val req_pri_rdy = Output(Bool())
    val req_sec_val = Input(Bool())
    val req_sec_rdy = Output(Bool())

    val brinfo       = Input(new BrResolutionInfo)
    val exception    = Input(Bool())
    val rob_pnr_idx  = Input(UInt(robAddrSz.W))
    val rob_head_idx = Input(UInt(robAddrSz.W))

    val req         = Input(new BoomDCacheReqInternal)

    val idx_match   = Output(Bool())
    val way_match   = Output(Bool())
    val tag_match   = Output(Bool())


    val mem_acquire = Decoupled(new TLBundleA(edge.bundle))

    val mem_grant   = Flipped(Decoupled(new TLBundleD(edge.bundle)))
    val mem_finish  = Decoupled(new TLBundleE(edge.bundle))

    val refill      = Decoupled(new L1DataWriteReq)

    val meta_write  = Decoupled(new L1MetaWriteReq)
    val meta_read   = Decoupled(new L1MetaReadReq)
    val meta_resp   = Input(Valid(new L1Metadata))
    val wb_req      = Decoupled(new WritebackReq(edge.bundle))

    // To inform the prefetcher when we are commiting the fetch of this line
    val commit_val  = Output(Bool())
    val commit_addr = Output(UInt(coreMaxAddrBits.W))
    val commit_coh  = Output(new ClientMetadata)

    // Reading from the line buffer
    val lb_alloc      = Decoupled(Bool())
    val lb_id         = Input(UInt(log2Ceil(nLBEntries).W))
    val lb_read       = Decoupled(new LineBufferReadReq)
    val lb_resp       = Input(UInt(encRowBits.W))
    val lb_write      = Decoupled(new LineBufferWriteReq)
    val lb_meta_write = Valid(new LineBufferMetaWriteReq)
    val lb_free       = Output(Valid(UInt(log2Ceil(nLBEntries).W))) // Throw away the linebuffer entry

    // Replays go through the cache pipeline again
    val replay      = Decoupled(new BoomDCacheReqInternal)
    // Resp go straight out to the core
    val resp        = Decoupled(new BoomDCacheResp)

    // Writeback unit tells us when it is done processing our wb
    val wb_resp     = Input(Bool())

    val probe_rdy   = Output(Bool())
  })

  // TODO: Optimize this. We don't want to mess with cache during speculation
  // s_refill_req      : Make a request for a new cache line
  // s_refill_resp     : Store the refill response into our buffer
  // s_drain_rpq_loads : Drain out loads from the rpq
  //                   : If miss was misspeculated, go to s_invalid
  // s_wb_req          : Write back the evicted cache line
  // s_wb_resp         : Finish writing back the evicted cache line
  // s_meta_write_req  : Write the metadata for new cache lne
  // s_meta_write_resp :

  val s_invalid :: s_alloc_lb :: s_refill_req :: s_refill_resp :: s_drain_rpq_loads :: s_meta_read :: s_meta_resp_1 :: s_meta_resp_2 :: s_meta_clear :: s_wb_meta_read :: s_wb_req :: s_wb_resp :: s_commit_line :: s_drain_rpq :: s_meta_write_req :: s_mem_finish :: Nil = Enum(16)
  val state = RegInit(s_invalid)

  val req     = Reg(new BoomDCacheReqInternal)
  val req_idx = req.addr(untagBits-1, blockOffBits)
  val req_tag = req.addr >> untagBits
  val req_block_addr = (req.addr >> blockOffBits) << blockOffBits
  val req_needs_wb = RegInit(false.B)
  val idx_match = req_idx === io.req.addr(untagBits-1, blockOffBits)
  val way_match = req.way_en === io.req.way_en
  val tag_match = req_tag === io.req.addr >> untagBits
  val tag_match_old = req.old_meta.tag === io.req.addr >> untagBits

  val new_coh = RegInit(ClientMetadata.onReset)
  val (_, shrink_param, coh_on_clear) = req.old_meta.coh.onCacheControl(M_FLUSH)
  val grow_param = new_coh.onAccess(req.uop.mem_cmd)._2
  val coh_on_grant = new_coh.onGrant(req.uop.mem_cmd, io.mem_grant.bits.param)

  // We only accept secondary misses if the original request had sufficient permissions
  val (cmd_requires_second_acquire, is_hit_again, _, dirtier_coh, dirtier_cmd) =
    new_coh.onSecondaryAccess(req.uop.mem_cmd, io.req.uop.mem_cmd)

  val (_, _, refill_done, refill_address_inc) = edge.addr_inc(io.mem_grant)
  val sec_rdy = (idx_match &&
                 !cmd_requires_second_acquire &&
                 !state.isOneOf(s_invalid, s_meta_write_req, s_mem_finish))// Always accept secondary misses

  val rpq = Module(new BranchKillableQueue(new BoomDCacheReqInternal, cfg.nRPQ, u => u.uses_ldq, false))
  rpq.io.brinfo := io.brinfo
  rpq.io.flush  := io.exception
  assert(!(state === s_invalid && !rpq.io.empty))

  rpq.io.enq.valid := ((io.req_pri_val && io.req_pri_rdy) || (io.req_sec_val && io.req_sec_rdy)) && !isPrefetch(io.req.uop.mem_cmd)
  rpq.io.enq.bits  := io.req
  rpq.io.deq.ready := false.B


  val grantack = Reg(Valid(new TLBundleE(edge.bundle)))
  val lb_id          = Reg(UInt(log2Ceil(nLBEntries).W))
  val lb_needs_clear = RegInit(false.B)
  val refill_ctr  = Reg(UInt(log2Ceil(cacheDataBeats).W))
  val commit_line = Reg(Bool())
  val grant_had_data = Reg(Bool())

  // Block probes if a tag write we started is still in the pipeline
  val meta_hazard = RegInit(0.U(2.W))
  when (meta_hazard =/= 0.U) { meta_hazard := meta_hazard + 1.U }
  when (io.meta_write.fire()) { meta_hazard := 1.U }
  io.probe_rdy   := (meta_hazard === 0.U && state.isOneOf(s_invalid, s_alloc_lb, s_refill_req, s_refill_resp)) || !idx_match
  io.idx_match   := (state =/= s_invalid) && idx_match
  io.way_match   := !state.isOneOf(s_invalid, s_refill_req, s_refill_resp, s_drain_rpq_loads) && way_match
  io.tag_match   := (state =/= s_invalid) && tag_match
  io.meta_write.valid    := false.B
  io.req_pri_rdy         := false.B
  io.req_sec_rdy         := sec_rdy && rpq.io.enq.ready
  io.mem_acquire.valid   := false.B
  io.refill.valid        := false.B
  io.replay.valid        := false.B
  io.wb_req.valid        := false.B
  io.resp.valid          := false.B
  io.commit_val          := false.B
  io.commit_addr         := req.addr
  io.commit_coh          := coh_on_grant
  io.meta_read.valid     := false.B
  io.mem_finish.valid    := false.B
  io.lb_alloc.valid      := false.B
  io.lb_alloc.bits       := DontCare
  io.lb_write.valid      := false.B
  io.lb_free.valid       := false.B
  io.lb_read.valid       := false.B
  io.lb_meta_write.valid := false.B

  when (io.req_sec_val && io.req_sec_rdy) {
    req.uop.mem_cmd := dirtier_cmd
    when (is_hit_again) {
      new_coh := dirtier_coh
    }
  }


  when (state === s_invalid) {
    io.req_pri_rdy := true.B
    grant_had_data := false.B

    when (io.req_pri_val && io.req_pri_rdy) {
      grantack.valid := false.B
      refill_ctr := 0.U
      assert(rpq.io.enq.ready)
      req := io.req
      val old_coh   = io.req.old_meta.coh
      req_needs_wb := old_coh.onCacheControl(M_FLUSH)._1 // does the line we are evicting need to be written back

      when (io.req.from_lb) {
        val (is_hit, _, coh_on_hit) = io.req.lb_coh.onAccess(io.req.uop.mem_cmd)
        lb_id          := io.req.lb_id
        lb_needs_clear := true.B
        new_coh        := coh_on_hit
        state          := s_drain_rpq_loads
        when (is_hit) { // Proceed with refill
          new_coh := coh_on_hit
          state   := s_meta_read
        } .otherwise { // Reacquire this line
          new_coh := ClientMetadata.onReset
          state   := s_refill_req
        }
      } .elsewhen (io.req.tag_match) {
        val (is_hit, _, coh_on_hit) = old_coh.onAccess(io.req.uop.mem_cmd)
        when (is_hit) { // set dirty bit
          assert(isWrite(io.req.uop.mem_cmd))
          new_coh := coh_on_hit
          state   := s_drain_rpq
        } .otherwise { // upgrade permissions
          new_coh := old_coh
          state   := s_alloc_lb
        }
      } .otherwise { // refill and writeback if necessary
        new_coh := ClientMetadata.onReset
        state   := s_alloc_lb
      }
    }
  } .elsewhen (state === s_alloc_lb) {
    io.lb_alloc.valid := true.B
    when (io.lb_alloc.fire()) {
      lb_id          := io.lb_id
      state          := s_refill_req
    }

  } .elsewhen (state === s_refill_req) {
    io.mem_acquire.valid := true.B
    // TODO: Use AcquirePerm if just doing permissions acquire
    io.mem_acquire.bits  := edge.AcquireBlock(
      fromSource      = id.U,
      toAddress       = Cat(req_tag, req_idx) << blockOffBits,
      lgSize          = lgCacheBlockBytes.U,
      growPermissions = grow_param)._2
    when (io.mem_acquire.fire()) {
      state := s_refill_resp
    }
  } .elsewhen (state === s_refill_resp) {
    when (edge.hasData(io.mem_grant.bits)) {
      io.mem_grant.ready      := io.lb_write.ready
      io.lb_write.valid       := io.mem_grant.valid
      io.lb_write.bits.id     := lb_id
      io.lb_write.bits.offset := refill_address_inc >> rowOffBits
      io.lb_write.bits.data   := io.mem_grant.bits.data
    } .otherwise {
      io.mem_grant.ready      := true.B
    }

    when (io.mem_grant.fire()) {
      grant_had_data := edge.hasData(io.mem_grant.bits)
    }
    when (refill_done) {
      grantack.valid := edge.isRequest(io.mem_grant.bits)
      grantack.bits := edge.GrantAck(io.mem_grant.bits)
      state := Mux(grant_had_data, s_drain_rpq_loads, s_drain_rpq)
      assert(!(!grant_had_data && req_needs_wb))
      commit_line := false.B
      new_coh := coh_on_grant

    }
  } .elsewhen (state === s_drain_rpq_loads) {
    val drain_load = (isRead(rpq.io.deq.bits.uop.mem_cmd) &&
                     !isWrite(rpq.io.deq.bits.uop.mem_cmd) &&
                     (rpq.io.deq.bits.uop.mem_cmd =/= M_XLR)) // LR should go through replay
    // drain all loads for now
    val rp_addr = Cat(req_tag, req_idx, rpq.io.deq.bits.addr(blockOffBits-1,0))
    val word_idx  = if (rowWords == 1) 0.U else rp_addr(log2Up(rowWords*coreDataBytes)-1, log2Up(wordBytes))
    val data      = io.lb_resp
    val data_word = data >> Cat(word_idx, 0.U(log2Up(coreDataBits).W))
    val loadgen = new LoadGen(rpq.io.deq.bits.uop.mem_size, rpq.io.deq.bits.uop.mem_signed,
      Cat(req_tag, req_idx, rpq.io.deq.bits.addr(blockOffBits-1,0)),
      data_word, false.B, wordBytes)


    rpq.io.deq.ready       := io.resp.ready && io.lb_read.ready && drain_load
    io.lb_read.valid       := rpq.io.deq.valid && drain_load
    io.lb_read.bits.id     := lb_id
    io.lb_read.bits.offset := rpq.io.deq.bits.addr >> rowOffBits

    io.resp.valid     := rpq.io.deq.valid && io.lb_read.fire() && drain_load
    io.resp.bits.uop  := rpq.io.deq.bits.uop
    io.resp.bits.data := loadgen.data
    io.resp.bits.is_hella := rpq.io.deq.bits.is_hella
    when (rpq.io.deq.fire()) {
      commit_line   := true.B
    }
      .elsewhen (rpq.io.empty && !commit_line)
    {
      when (!rpq.io.enq.fire()) {
        io.lb_meta_write.valid       := true.B
        io.lb_meta_write.bits.id     := lb_id
        io.lb_meta_write.bits.coh    := new_coh
        io.lb_meta_write.bits.addr   := req.addr // TODO: Truncate the block offset bits
        if (!enablePrefetching) {
          lb_needs_clear := true.B
        }
        state := s_mem_finish
      }
    } .elsewhen (rpq.io.empty || (rpq.io.deq.valid && !drain_load)) {
      lb_needs_clear := true.B
      io.commit_val := true.B
      state := s_meta_read
    }
  } .elsewhen (state === s_meta_read) {
    io.meta_read.valid := true.B
    io.meta_read.bits.idx := req_idx
    io.meta_read.bits.tag := req_tag
    io.meta_read.bits.way_en := req.way_en
    when (io.meta_read.fire()) {
      state := s_meta_resp_1
    }
  } .elsewhen (state === s_meta_resp_1) {
    state := s_meta_resp_2
  } .elsewhen (state === s_meta_resp_2) {
    val needs_wb = io.meta_resp.bits.coh.onCacheControl(M_FLUSH)._1
    state := Mux(!io.meta_resp.valid, s_meta_read, // Prober could have nack'd this read
             Mux(needs_wb, s_meta_clear, s_commit_line))
  } .elsewhen (state === s_meta_clear) {
    io.meta_write.valid         := true.B
    io.meta_write.bits.idx      := req_idx
    io.meta_write.bits.data.coh := coh_on_clear
    io.meta_write.bits.data.tag := req_tag
    io.meta_write.bits.way_en   := req.way_en

    when (io.meta_write.fire()) {
      state      := s_wb_req
    }
  } .elsewhen (state === s_wb_req) {
    io.wb_req.valid          := true.B

    io.wb_req.bits.tag       := req.old_meta.tag
    io.wb_req.bits.idx       := req_idx
    io.wb_req.bits.param     := shrink_param
    io.wb_req.bits.way_en    := req.way_en
    io.wb_req.bits.source    := id.U
    io.wb_req.bits.voluntary := true.B
    when (io.wb_req.fire()) {
      state := s_wb_resp
    }
  } .elsewhen (state === s_wb_resp) {
    when (io.wb_resp) {
      state := s_commit_line
    }
  } .elsewhen (state === s_commit_line) {
    io.lb_read.valid       := true.B
    io.lb_read.bits.id     := lb_id
    io.lb_read.bits.offset := refill_ctr

    io.refill.valid       := io.lb_read.fire()
    io.refill.bits.addr   := req_block_addr | (refill_ctr << rowOffBits)
    io.refill.bits.way_en := req.way_en
    io.refill.bits.wmask  := ~(0.U(rowWords.W))
    io.refill.bits.data   := io.lb_resp
    when (io.refill.fire()) {
      refill_ctr := refill_ctr + 1.U
      when (refill_ctr === (cacheDataBeats - 1).U) {
        state := s_drain_rpq
      }
    }
  } .elsewhen (state === s_drain_rpq) {
    io.replay <> rpq.io.deq
    io.replay.bits.way_en    := req.way_en
    io.replay.bits.addr := Cat(req_tag, req_idx, rpq.io.deq.bits.addr(blockOffBits-1,0))
    when (io.replay.fire() && isWrite(rpq.io.deq.bits.uop.mem_cmd)) {
      // Set dirty bit
      val (is_hit, _, coh_on_hit) = new_coh.onAccess(rpq.io.deq.bits.uop.mem_cmd)
      assert(is_hit, "We still don't have permissions for this store")
      new_coh := coh_on_hit
    }
    when (rpq.io.empty && !rpq.io.enq.valid) {
      state := s_meta_write_req
    }
  } .elsewhen (state === s_meta_write_req) {
    io.meta_write.valid         := true.B
    io.meta_write.bits.idx      := req_idx
    io.meta_write.bits.data.coh := new_coh
    io.meta_write.bits.data.tag := req_tag
    io.meta_write.bits.way_en   := req.way_en
    when (io.meta_write.fire()) {
      state := s_mem_finish
    }
  } .elsewhen (state === s_mem_finish) {
    io.mem_finish.valid := grantack.valid
    io.mem_finish.bits  := grantack.bits
    io.lb_free.valid    := lb_needs_clear
    io.lb_free.bits     := lb_id
    lb_needs_clear      := false.B
    when (io.mem_finish.fire() || !grantack.valid) {
      grantack.valid := false.B
      state := s_invalid
    }
  }


}

class BoomIOMSHR(id: Int)(implicit edge: TLEdgeOut, p: Parameters) extends BoomModule()(p)
  with HasL1HellaCacheParameters
{
  val io = IO(new Bundle {
    val req  = Flipped(Decoupled(new BoomDCacheReq))
    val resp = Decoupled(new BoomDCacheResp)
    val mem_access = Decoupled(new TLBundleA(edge.bundle))
    val mem_ack    = Flipped(Valid(new TLBundleD(edge.bundle)))

    // We don't need brinfo in here because uncacheable operations are guaranteed non-speculative
  })

  def beatOffset(addr: UInt) = addr.extract(beatOffBits-1, wordOffBits)

  def wordFromBeat(addr: UInt, dat: UInt) = {
    val shift = Cat(beatOffset(addr), 0.U((wordOffBits+log2Ceil(wordBytes)).W))
    (dat >> shift)(wordBits-1, 0)
  }

  val req = Reg(new BoomDCacheReq)
  val grant_word = Reg(UInt(wordBits.W))

  val s_idle :: s_mem_access :: s_mem_ack :: s_resp :: Nil = Enum(4)

  val state = RegInit(s_idle)
  io.req.ready := state === s_idle

  val loadgen = new LoadGen(req.uop.mem_size, req.uop.mem_signed, req.addr, grant_word, false.B, wordBytes)

  val a_source  = id.U
  val a_address = req.addr
  val a_size    = req.uop.mem_size
  val a_data    = Fill(beatWords, req.data)

  val get      = edge.Get(a_source, a_address, a_size)._2
  val put      = edge.Put(a_source, a_address, a_size, a_data)._2
  val atomics  = if (edge.manager.anySupportLogical) {
    MuxLookup(req.uop.mem_cmd, (0.U).asTypeOf(new TLBundleA(edge.bundle)), Array(
      M_XA_SWAP -> edge.Logical(a_source, a_address, a_size, a_data, TLAtomics.SWAP)._2,
      M_XA_XOR  -> edge.Logical(a_source, a_address, a_size, a_data, TLAtomics.XOR) ._2,
      M_XA_OR   -> edge.Logical(a_source, a_address, a_size, a_data, TLAtomics.OR)  ._2,
      M_XA_AND  -> edge.Logical(a_source, a_address, a_size, a_data, TLAtomics.AND) ._2,
      M_XA_ADD  -> edge.Arithmetic(a_source, a_address, a_size, a_data, TLAtomics.ADD)._2,
      M_XA_MIN  -> edge.Arithmetic(a_source, a_address, a_size, a_data, TLAtomics.MIN)._2,
      M_XA_MAX  -> edge.Arithmetic(a_source, a_address, a_size, a_data, TLAtomics.MAX)._2,
      M_XA_MINU -> edge.Arithmetic(a_source, a_address, a_size, a_data, TLAtomics.MINU)._2,
      M_XA_MAXU -> edge.Arithmetic(a_source, a_address, a_size, a_data, TLAtomics.MAXU)._2))
  } else {
    // If no managers support atomics, assert fail if processor asks for them
    assert(state === s_idle || !isAMO(req.uop.mem_cmd))
    Wire(new TLBundleA(edge.bundle))
  }
  assert(state === s_idle || req.uop.mem_cmd =/= M_XSC)

  io.mem_access.valid := state === s_mem_access
  io.mem_access.bits  := Mux(isAMO(req.uop.mem_cmd), atomics, Mux(isRead(req.uop.mem_cmd), get, put))

  val send_resp = isRead(req.uop.mem_cmd)

  io.resp.valid     := (state === s_resp) && send_resp
  io.resp.bits.uop  := req.uop
  io.resp.bits.data := loadgen.data

  when (io.req.fire()) {
    req   := io.req.bits
    state := s_mem_access
  }
  when (io.mem_access.fire()) {
    state := s_mem_ack
  }
  when (state === s_mem_ack && io.mem_ack.valid) {
    state := s_resp
    when (isRead(req.uop.mem_cmd)) {
      grant_word := wordFromBeat(req.addr, io.mem_ack.bits.data)
    }
  }
  when (state === s_resp) {
    when (!send_resp || io.resp.fire()) {
      state := s_idle
    }
  }
}

class LineBufferReadReq(implicit p: Parameters) extends BoomBundle()(p)
  with HasL1HellaCacheParameters
{
  val id      = UInt(log2Ceil(nLBEntries).W)
  val offset  = UInt(log2Ceil(cacheDataBeats).W)
  def lb_addr = Cat(id, offset)
}

class LineBufferWriteReq(implicit p: Parameters) extends LineBufferReadReq()(p)
{
  val data   = UInt(encRowBits.W)
}

class LineBufferMetaWriteReq(implicit p: Parameters) extends BoomBundle()(p)
{
  val id   = UInt(log2Ceil(nLBEntries).W)
  val coh  = new ClientMetadata
  val addr = UInt(coreMaxAddrBits.W)
}

class LineBufferMeta(implicit p: Parameters) extends BoomBundle()(p)
  with HasL1HellaCacheParameters
{
  val coh  = new ClientMetadata
  val addr = UInt(coreMaxAddrBits.W)
}

class BoomMSHRFile(implicit edge: TLEdgeOut, p: Parameters) extends BoomModule()(p)
  with HasL1HellaCacheParameters
{
  val io = IO(new Bundle {
    val s1_req = Input(Valid(new BoomDCacheReq)) // Req from s1 of DCache pipe
    val req  = Flipped(Decoupled(new BoomDCacheReqInternal)) // Req from s2 of DCache pipe
    val req_is_probe = Input(Bool())
    val resp = Decoupled(new BoomDCacheResp)
    val secondary_miss = Output(Bool())
    val block_hit = Output(Bool())

    val brinfo       = Input(new BrResolutionInfo)
    val exception    = Input(Bool())
    val rob_pnr_idx  = Input(UInt(robAddrSz.W))
    val rob_head_idx = Input(UInt(robAddrSz.W))

    val mem_acquire  = Decoupled(new TLBundleA(edge.bundle))
    val mem_grant    = Flipped(Decoupled(new TLBundleD(edge.bundle)))
    val mem_finish   = Decoupled(new TLBundleE(edge.bundle))

    val refill     = Decoupled(new L1DataWriteReq)
    val meta_write = Decoupled(new L1MetaWriteReq)
    val meta_read  = Decoupled(new L1MetaReadReq)
    val meta_resp  = Input(Valid(new L1Metadata))
    val replay     = Decoupled(new BoomDCacheReqInternal)
    val prefetch   = Decoupled(new BoomDCacheReq)
    val wb_req     = Decoupled(new WritebackReq(edge.bundle))

    val clear_all = Input(Bool()) // Clears all uncommitted MSHRs to prepare for fence

    val wb_resp   = Input(Bool())

    val fence_rdy = Output(Bool())
    val probe_rdy = Output(Bool())
  })

  assert(!(io.req.valid && !RegNext(io.s1_req.valid)))

  val prefetcher: DataPrefetcher = if (enablePrefetching) Module(new NLPrefetcher)
                                                     else Module(new NullPrefetcher)

  io.prefetch <> prefetcher.io.prefetch


  val cacheable = edge.manager.supportsAcquireBFast(io.req.bits.addr, lgCacheBlockBytes.U)

  // --------------------
  // The MSHR SDQ
  val sdq_val      = RegInit(0.U(cfg.nSDQ.W))
  val sdq_alloc_id = PriorityEncoder(~sdq_val(cfg.nSDQ-1,0))
  val sdq_rdy      = !sdq_val.andR
  val sdq_enq      = io.req.fire() && cacheable && isWrite(io.req.bits.uop.mem_cmd)
  val sdq          = Mem(cfg.nSDQ, UInt(coreDataBits.W))

  when (sdq_enq) {
    sdq(sdq_alloc_id) := io.req.bits.data
  }

  // --------------------
  // The LineBuffer Data
  // Holds refilling lines, prefetched lines

  val lb_invalid :: lb_in_use :: lb_prefetched :: Nil = Enum(3)
  val lb = Mem(nLBEntries * cacheDataBeats, UInt(encRowBits.W))
  val lb_state   = RegInit(VecInit(Seq.fill(nLBEntries) { lb_invalid }))
  val lb_head    = RegInit(0.U(log2Ceil(nLBEntries).W))
  val lb_alloc_idx = RegNext(AgePriorityEncoder(lb_state.map(s => s === lb_invalid), lb_head))
  val lb_do_free = WireInit(false.B)
  val lb_free_idx = RegNext(AgePriorityEncoder(lb_state.map(s => s === lb_prefetched),
    random.LFSR(log2Ceil(nLBEntries))))

  val lb_meta = Reg(Vec(nLBEntries, new LineBufferMeta))

  val lb_alloc_arb = Module(new Arbiter(Bool(), cfg.nMSHRs))
  val lb_read_arb  = Module(new Arbiter(new LineBufferReadReq, cfg.nMSHRs))
  val lb_write_arb = Module(new Arbiter(new LineBufferWriteReq, cfg.nMSHRs))

  lb_alloc_arb.io.out.ready := lb_state(lb_alloc_idx) === lb_invalid
  when (lb_alloc_arb.io.out.fire()) {
    lb_head := WrapInc(lb_head, nLBEntries)
    lb_state(lb_alloc_idx) := lb_in_use
  } .elsewhen (lb_alloc_arb.io.out.valid) {
    lb_do_free := true.B
    when (lb_state(lb_free_idx) === lb_prefetched) {
      lb_state(lb_free_idx) := lb_invalid
    }
  }

  lb_read_arb.io.out.ready  := false.B
  lb_write_arb.io.out.ready := true.B

  val lb_read_data = WireInit(0.U(encRowBits.W))
  when (lb_write_arb.io.out.fire()) {
    assert(lb_state(lb_write_arb.io.out.bits.id) === lb_in_use)
    lb.write(lb_write_arb.io.out.bits.lb_addr, lb_write_arb.io.out.bits.data)
  } .otherwise {
    lb_read_arb.io.out.ready := true.B
    when (lb_read_arb.io.out.fire()) {
      lb_read_data := lb.read(lb_read_arb.io.out.bits.lb_addr)
    }
  }

  val s1_lb_addr_matches = VecInit((lb_meta zip lb_state) .map { case (m, s) =>
    (io.s1_req.bits.addr >> blockOffBits === m.addr >> blockOffBits) && s === lb_prefetched && enablePrefetching.B
  })
  val s2_lb_addr_matches = RegNext(s1_lb_addr_matches)
  val s2_lb_addr_match   = s2_lb_addr_matches.orR
  val s2_lb_hit_idx      = OHToUInt(s2_lb_addr_matches)
  val s2_lb_hit_state    = Mux1H(s2_lb_addr_matches, lb_meta.map(_.coh))
  val s2_lb_hit          = io.req.valid && s2_lb_addr_match
  when (s2_lb_hit && io.req.fire()) {
    lb_state(s2_lb_hit_idx) := lb_in_use
  }


  // Probes clear prefetched lines out of LineBuffer
  for (i <- 0 until nLBEntries) {
    when (io.req_is_probe && s2_lb_addr_matches(i) && lb_meta(i).coh.isValid()) {
      lb_state(i) := lb_invalid
    }
  }


  val idx_matches = Wire(Vec(cfg.nMSHRs, Bool()))
  val tag_matches = Wire(Vec(cfg.nMSHRs, Bool()))
  val tag_match   = Mux1H(idx_matches, tag_matches)

  val wb_tag_list = Wire(Vec(cfg.nMSHRs, UInt(tagBits.W)))

  val meta_write_arb = Module(new Arbiter(new L1MetaWriteReq           , cfg.nMSHRs))
  val meta_read_arb  = Module(new Arbiter(new L1MetaReadReq            , cfg.nMSHRs))
  val wb_req_arb     = Module(new Arbiter(new WritebackReq(edge.bundle), cfg.nMSHRs))
  val replay_arb     = Module(new Arbiter(new BoomDCacheReqInternal    , cfg.nMSHRs))
  val resp_arb       = Module(new Arbiter(new BoomDCacheResp           , cfg.nMSHRs + nIOMSHRs))
  val refill_arb     = Module(new Arbiter(new L1DataWriteReq           , cfg.nMSHRs))

  val commit_vals    = Wire(Vec(cfg.nMSHRs, Bool()))
  val commit_addrs   = Wire(Vec(cfg.nMSHRs, UInt(coreMaxAddrBits.W)))
  val commit_cohs    = Wire(Vec(cfg.nMSHRs, new ClientMetadata))

  var way_match = false.B
  var sec_rdy   = false.B

  io.fence_rdy := true.B
  io.probe_rdy := true.B
  io.mem_grant.ready := false.B

  val idx_match = idx_matches.reduce(_||_)
  val mshr_alloc_idx = Wire(UInt())
  val pri_rdy = WireInit(false.B)
  val pri_val = io.req.valid && sdq_rdy && cacheable && !idx_match
  val mshrs = (0 until cfg.nMSHRs) map { i =>
    val mshr = Module(new BoomMSHR(i))

    idx_matches(i) := mshr.io.idx_match
    tag_matches(i) := mshr.io.tag_match
    wb_tag_list(i) := mshr.io.wb_req.bits.tag



    mshr.io.req_pri_val  := (i.U === mshr_alloc_idx) && pri_val
    when (i.U === mshr_alloc_idx) {
      pri_rdy := mshr.io.req_pri_rdy
    }

    mshr.io.req_sec_val  := io.req.valid && sdq_rdy && tag_match && cacheable
    mshr.io.req          := io.req.bits
    mshr.io.req.sdq_id   := sdq_alloc_id
    mshr.io.req.from_lb  := s2_lb_hit
    mshr.io.req.lb_id    := s2_lb_hit_idx
    mshr.io.req.lb_coh   := s2_lb_hit_state

    mshr.io.brinfo       := io.brinfo
    mshr.io.exception    := io.exception
    mshr.io.rob_pnr_idx  := io.rob_pnr_idx
    mshr.io.rob_head_idx := io.rob_head_idx

    mshr.io.wb_resp      := io.wb_resp

    meta_write_arb.io.in(i) <> mshr.io.meta_write
    meta_read_arb.io.in(i)  <> mshr.io.meta_read
    mshr.io.meta_resp       := io.meta_resp
    wb_req_arb.io.in(i)     <> mshr.io.wb_req
    replay_arb.io.in(i)     <> mshr.io.replay
    refill_arb.io.in(i)     <> mshr.io.refill

    lb_alloc_arb.io.in(i)      <> mshr.io.lb_alloc
    mshr.io.lb_id              := lb_alloc_idx
    lb_read_arb.io.in(i)       <> mshr.io.lb_read
    mshr.io.lb_resp            := lb_read_data
    lb_write_arb.io.in(i)      <> mshr.io.lb_write

    when (mshr.io.lb_meta_write.valid) {
      assert(lb_state(mshr.io.lb_meta_write.bits.id) === lb_in_use)
      lb_meta(mshr.io.lb_meta_write.bits.id).coh  := mshr.io.lb_meta_write.bits.coh
      lb_meta(mshr.io.lb_meta_write.bits.id).addr := mshr.io.lb_meta_write.bits.addr

      lb_state(mshr.io.lb_meta_write.bits.id)     := lb_prefetched
    }
    when (mshr.io.lb_free.valid) {
      lb_state(mshr.io.lb_free.bits)    := lb_invalid
      lb_meta(mshr.io.lb_free.bits).coh := ClientMetadata.onReset
    }

    commit_vals(i)  := mshr.io.commit_val
    commit_addrs(i) := mshr.io.commit_addr
    commit_cohs(i)  := mshr.io.commit_coh

    mshr.io.mem_grant.valid := false.B
    mshr.io.mem_grant.bits  := DontCare
    when (io.mem_grant.bits.source === i.U) {
      mshr.io.mem_grant <> io.mem_grant
    }

    sec_rdy   = sec_rdy || mshr.io.req_sec_rdy
    way_match = way_match || mshr.io.way_match

    resp_arb.io.in(i) <> mshr.io.resp

    when (!mshr.io.req_pri_rdy) {
      io.fence_rdy := false.B
    }
    when (!mshr.io.probe_rdy) {
      io.probe_rdy := false.B
    }

    mshr
  }

  // Try to round-robin the MSHRs
  val mshr_head      = RegInit(0.U(log2Ceil(cfg.nMSHRs).W))
  mshr_alloc_idx    := RegNext(AgePriorityEncoder(mshrs.map(m=>m.io.req_pri_rdy), mshr_head))
  when (pri_rdy && pri_val) { mshr_head := WrapInc(mshr_head, cfg.nMSHRs) }



  io.meta_write <> meta_write_arb.io.out
  io.meta_read  <> meta_read_arb.io.out
  io.wb_req     <> wb_req_arb.io.out

  val mmio_alloc_arb = Module(new Arbiter(Bool(), nIOMSHRs))


  var mmio_rdy = false.B

  val mmios = (0 until nIOMSHRs) map { i =>
    val id = cfg.nMSHRs + 1 + i // +1 for wb unit
    val mshr = Module(new BoomIOMSHR(id))

    mmio_alloc_arb.io.in(i).valid := mshr.io.req.ready
    mmio_alloc_arb.io.in(i).bits  := DontCare
    mshr.io.req.valid := mmio_alloc_arb.io.in(i).ready
    mshr.io.req.bits  := io.req.bits

    mmio_rdy = mmio_rdy || mshr.io.req.ready

    mshr.io.mem_ack.bits  := io.mem_grant.bits
    mshr.io.mem_ack.valid := io.mem_grant.valid && io.mem_grant.bits.source === id.U
    when (io.mem_grant.bits.source === id.U) {
      io.mem_grant.ready := true.B
    }

    resp_arb.io.in(cfg.nMSHRs + i) <> mshr.io.resp
    when (!mshr.io.req.ready) {
      io.fence_rdy := false.B
    }
    mshr
  }

  mmio_alloc_arb.io.out.ready := io.req.valid && !cacheable

  TLArbiter.lowestFromSeq(edge, io.mem_acquire, mshrs.map(_.io.mem_acquire) ++ mmios.map(_.io.mem_access))
  TLArbiter.lowestFromSeq(edge, io.mem_finish,  mshrs.map(_.io.mem_finish))

  io.resp           <> resp_arb.io.out
  io.req.ready      := Mux(!cacheable, mmio_rdy, sdq_rdy && Mux(idx_match, tag_match && sec_rdy, pri_rdy))
  io.secondary_miss := idx_match && way_match && !tag_match
  io.block_hit      := idx_match && tag_match
  io.refill         <> refill_arb.io.out

  val free_sdq = io.replay.fire() && isWrite(io.replay.bits.uop.mem_cmd)
  io.replay.bits.data := sdq(RegEnable(replay_arb.io.out.bits.sdq_id, free_sdq))

  io.replay <> replay_arb.io.out

  when (io.replay.valid || sdq_enq) {
    sdq_val := sdq_val & ~(UIntToOH(replay_arb.io.out.bits.sdq_id) & Fill(cfg.nSDQ, free_sdq)) |
      PriorityEncoderOH(~sdq_val(cfg.nSDQ-1,0)) & Fill(cfg.nSDQ, sdq_enq)
  }

  prefetcher.io.mshr_avail    := RegNext(pri_rdy)
  prefetcher.io.req_val       := RegNext(commit_vals.reduce(_||_))
  prefetcher.io.req_addr      := RegNext(Mux1H(commit_vals, commit_addrs))
  prefetcher.io.req_coh       := RegNext(Mux1H(commit_vals, commit_cohs))
}
