// Copyright (c) 2024 Beijing Institute of Open Source Chip (BOSC)
// Copyright (c) 2020-2024 Institute of Computing Technology, Chinese Academy of Sciences
// Copyright (c) 2020-2021 Peng Cheng Laboratory
//
// XiangShan is licensed under Mulan PSL v2.
// You can use this software according to the terms and conditions of the Mulan PSL v2.
// You may obtain a copy of Mulan PSL v2 at:
//          https://license.coscl.org.cn/MulanPSL2
//
// THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
// EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
// MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
//
// See the Mulan PSL v2 for more details.

package xiangshan.frontend.icache

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import utility.CircularQueuePtr
import utility.HasCircularQueuePtrHelper
import utility.XSError
import utility.XSPerfAccumulate
import utility.XSPerfHistogram
import utility.XSPerfSeqAccumulate
import xiangshan.frontend.ftq.BpuFlushInfo
import xiangshan.frontend.ftq.FtqPtr
import xiangshan.frontend.ftq.FtqToWayLookupBundle

class ICacheWayLookup(implicit p: Parameters) extends ICacheModule
    with ICacheMissUpdateHelper
    with HasCircularQueuePtrHelper {

  class ICacheWayLookupIO(implicit p: Parameters) extends ICacheBundle {
    val flush:        Bool         = Input(Bool())
    val flushFromBpu: BpuFlushInfo = Input(new BpuFlushInfo)

    val fromFtq:           DecoupledIO[FtqToWayLookupBundle] = Flipped(Decoupled(new FtqToWayLookupBundle))
    val realTwoFetchValid: Bool                              = Output(Bool()) // to FTQ

    val toMainPipe: DecoupledIO[WayLookupToMainPipeBundle] = DecoupledIO(new WayLookupToMainPipeBundle)

    val write: Vec[DecoupledIO[WayLookupWriteBundle]] = Vec(FetchPorts, Flipped(DecoupledIO(new WayLookupWriteBundle)))

    val update: Valid[MissRespBundle] = Flipped(ValidIO(new MissRespBundle))

    val perf: WayLookupPerfInfo = Output(new WayLookupPerfInfo)
  }

  val io: ICacheWayLookupIO = IO(new ICacheWayLookupIO)

  class ICacheWayLookupPtr extends CircularQueuePtr[ICacheWayLookupPtr](WayLookupSize)
  private object ICacheWayLookupPtr {
    def apply(f: Bool, v: UInt): ICacheWayLookupPtr = {
      val ptr = Wire(new ICacheWayLookupPtr)
      ptr.flag  := f
      ptr.value := v
      ptr
    }
  }

  private val entries  = RegInit(VecInit(Seq.fill(WayLookupSize)(0.U.asTypeOf(new WayLookupEntry))))
  private val readPtr  = RegInit(ICacheWayLookupPtr(false.B, 0.U))
  private val writePtr = RegInit(ICacheWayLookupPtr(false.B, 0.U))

  private val tailFtqIdx = RegInit(0.U.asTypeOf(new FtqPtr))

  private val empty = readPtr === writePtr

  private val numValidEntries = distanceBetween(writePtr, readPtr)
  private val numFreeEntries  = WayLookupSize.U - numValidEntries
  dontTouch(numValidEntries)
  dontTouch(numFreeEntries)

  // NOTE: May be unportable, we have bp3 == pf2 now, and WayLookup is written in pf1,
  // so the tailing 0 (already bypassed to if1) or 1 (if1 stall, stored here) entries might be flushed by bp3,
  // therefore, when shouldFlushByStage3, we need to move back writePtr by 0 (empty) or 1.
  // If in future we have bp4 (or even more) flush, this might not be enough.
  // NOTE: With 2-prefetch, writePtr - 2.U still does not need to be flushed,
  // as we ask the second fetch block to be flushed within Ftq. Refer to `canTwoPrefetch` condition in `class Ftq`
  private val bpuS3FlushValid = io.flushFromBpu.shouldFlushByStage3(tailFtqIdx, true.B)
  private val bpuS3FlushPtr   = writePtr - 1.U

  when(io.flush) {
    writePtr.value := 0.U
    writePtr.flag  := false.B
  }.elsewhen(bpuS3FlushValid && !empty) {
    writePtr := bpuS3FlushPtr
  }.elsewhen(io.write.head.fire) {
    // FetchPorts must be 1 or 2, and last.fire is depend on head.fire
    val enqCnt = Mux(io.write.last.fire, FetchPorts.U, 1.U)
    writePtr := writePtr + enqCnt
  }

  when(io.flush) {
    readPtr.value := 0.U
    readPtr.flag  := false.B
  }.elsewhen(io.toMainPipe.fire) {
    readPtr := readPtr + Mux(io.realTwoFetchValid, 2.U, 1.U)
  }

  when(io.flush) {
    tailFtqIdx.value := 0.U
    tailFtqIdx.flag  := false.B
  }.elsewhen(io.write.head.fire) {
    tailFtqIdx := Mux(io.write.last.fire, io.write.last.bits.ftqIdx, io.write.head.bits.ftqIdx)
  }

  // we can store only the first exception encountered, as exceptions must trigger a redirection (and thus a flush)
  private val exceptionEntry = RegInit(0.U.asTypeOf(Valid(new WayLookupExceptionEntry)))
  private val exceptionPtr   = RegInit(ICacheWayLookupPtr(false.B, 0.U))
  private val exceptionHit = VecInit(
    exceptionPtr === readPtr && exceptionEntry.valid,
    exceptionPtr === (readPtr + 1.U) && exceptionEntry.valid
  )

  when(io.flush || bpuS3FlushValid && exceptionPtr === bpuS3FlushPtr) {
    // When flushed by bp3
    // we don't need to reset exceptionEntry/Ptr to save power
    exceptionEntry.valid := false.B
  }

  /* *** update *** */
  private val entryUpdate = VecInit(entries.map { entry =>
    (0 until PortNumber).map { i =>
      val (updated, newInfo) = updateMetaInfo(
        io.update,
        entry.vSetIdx(i),
        entry.pTag,
        entry.getMetaInfo(i)
      )
      when(updated) {
        entry.updateMetaInfo(i, newInfo)
      }
      updated
    }.reduce(_ || _)
  })
  // if the entry is being updated, we should not read it (i.e. read.valid should be false)
  private val updateStall = VecInit(
    entryUpdate(readPtr.value),
    entryUpdate((readPtr + 1.U).value)
  )

  /* *** read *** */
  // if the entry is empty, but there is a valid write, we can bypass it to read port (maybe timing critical)
//  private val canBypass = empty && io.write.valid && !exceptionEntry.valid
  private val secondWriteValid = if (FetchPorts == 1) false.B else io.write(1).valid
  private val fetchReq         = io.fromFtq.bits.req
  private val rawTwoFetchValid = fetchReq(1).valid
  private val canBypass = empty && io.write(0).valid && !secondWriteValid && !rawTwoFetchValid && !exceptionEntry.valid
  private val canBypassFirst  = canBypass
  private val canBypassSecond = false.B // TODO

  private val canDeqFirst            = !empty && !updateStall(0)
  private val canDeqSecond           = numValidEntries > 1.U && !updateStall(0) && !updateStall(1)
  private val canServeFirst          = canBypassFirst || canDeqFirst
  private val canServeSecond         = canBypassSecond || canDeqSecond
  private val candidateTwoFetchValid = rawTwoFetchValid && canServeSecond
  private val canServe               = canServeFirst

  private val firstReqEntry  = Mux(canBypassFirst, io.write(0).bits.entry, entries(readPtr.value))
  private val secondReqEntry = Mux(canBypassSecond, io.write(1).bits.entry, entries((readPtr + 1.U).value))
  private val firstReqExceptionEntry = Mux(
    canBypassFirst,
    io.write(0).bits.exceptionEntry,
    Mux(exceptionHit(0), exceptionEntry.bits, 0.U.asTypeOf(new WayLookupExceptionEntry))
  )
  private val secondReqExceptionEntry = Mux(
    canBypassSecond,
    io.write(1).bits.exceptionEntry,
    Mux(exceptionHit(1), exceptionEntry.bits, 0.U.asTypeOf(new WayLookupExceptionEntry))
  )

  private val isDataSramReadConflict = (0 until DataBanks).map { bankIdx =>
    val firstReqLineSelOH  = Cat(fetchReq(0).bankSel(1)(bankIdx), fetchReq(0).bankSel(0)(bankIdx))
    val secondReqLineSelOH = Cat(fetchReq(1).bankSel(1)(bankIdx), fetchReq(1).bankSel(0)(bankIdx))

    when(io.fromFtq.fire) {
      assert(
        PopCount(firstReqLineSelOH) <= 1.U,
        "ICache Way Lookup: first req select two line one one bank"
      )
      when(fetchReq(1).valid) {
        assert(
          PopCount(secondReqLineSelOH) <= 1.U,
          "ICache Way Lookup: second req select two line one one bank"
        )
      }
    }

    val firstReqReadValid  = firstReqLineSelOH.orR && fetchReq(0).valid
    val secondReqReadValid = secondReqLineSelOH.orR && fetchReq(1).valid
    val bothRead           = firstReqReadValid && secondReqReadValid

    val firstReqReadWayMask  = Mux1H(firstReqLineSelOH, firstReqEntry.waymask) & Fill(nWays, firstReqReadValid)
    val secondReqReadWayMask = Mux1H(secondReqLineSelOH, secondReqEntry.waymask) & Fill(nWays, secondReqReadValid)
    val readSameWay          = firstReqReadWayMask === secondReqReadWayMask

    val firstReqReadSetIdx  = Mux1H(firstReqLineSelOH, fetchReq(0).vSetIdx)
    val secondReqReadSetIdx = Mux1H(secondReqLineSelOH, fetchReq(1).vSetIdx)

    bothRead && readSameWay && (firstReqReadSetIdx =/= secondReqReadSetIdx)
  }.reduce(_ || _)

  private val hasMmio             = firstReqEntry.isMmio || secondReqEntry.isMmio
  private val hasItlbException    = exceptionHit(0) || exceptionHit(1)
  private val hasBackendException = fetchReq(0).hasBackendException || fetchReq(1).hasBackendException
  private val realTwoFetchValid =
    candidateTwoFetchValid &&
      !isDataSramReadConflict &&
      !hasMmio &&
      !hasItlbException &&
      !hasBackendException
  io.realTwoFetchValid := realTwoFetchValid

  io.toMainPipe.valid             := io.fromFtq.valid && canServe && !io.flush
  io.toMainPipe.bits.req          := fetchReq
  io.toMainPipe.bits.req(1).valid := fetchReq(1).valid && realTwoFetchValid

  io.toMainPipe.bits.wayLookupInfo(0).entry          := firstReqEntry
  io.toMainPipe.bits.wayLookupInfo(0).exceptionEntry := firstReqExceptionEntry
  io.toMainPipe.bits.wayLookupInfo(1).entry          := secondReqEntry
  io.toMainPipe.bits.wayLookupInfo(1).exceptionEntry := secondReqExceptionEntry

  io.fromFtq.ready := io.toMainPipe.ready && canServe && !io.flush

  when(io.toMainPipe.fire) {
    assert(io.toMainPipe.bits.wayLookupInfo(0).debug_ftqIdx === fetchReq(0).ftqIdx)
    assert(io.toMainPipe.bits.wayLookupInfo(0).debug_startVAddr === fetchReq(0).startVAddr)
    when(realTwoFetchValid) {
      assert(io.toMainPipe.bits.wayLookupInfo(1).debug_ftqIdx === fetchReq(1).ftqIdx)
      assert(io.toMainPipe.bits.wayLookupInfo(1).debug_startVAddr === fetchReq(1).startVAddr)
    }
  }

  /**
    ******************************************************************************
    * write
    ******************************************************************************
    */
  // stall write if there is an exceptions to save power (i.e. wait for flush)
  // this will stall the prefetch pipe
  // also we disallow only 1 ready, to simplify PrefetchPipe
  io.write.foreach(_.ready := numFreeEntries >= FetchPorts.U && !exceptionEntry.valid)
  when(io.write.head.fire) {
    entries(writePtr.value) := io.write.head.bits.entry
    if (FetchPorts > 1) {
      when(io.write.last.fire) {
        entries(writePtr.value + 1.U) := io.write.last.bits.entry
      }
    }
    // ftq/prefetchPipe ensure fetch blocks has the same exception, here we can consider only .head
    when(io.write.head.bits.itlbException.hasException) {
      exceptionEntry.valid := true.B
      exceptionEntry.bits  := io.write.head.bits.exceptionEntry
      exceptionPtr         := writePtr
    }
  }
  // the second port (if FetchPorts == 2) must fire together with the first port
  XSError(io.write.last.fire && !io.write.head.fire, "2-prefetch port fire without first port fire")

  /* *** perf *** */
  // tell ICache top if queue is empty
  io.perf.empty := empty

  // perf counter
  // occupancy
  XSPerfHistogram(
    "occupiedEntryCnt",
    distanceBetween(writePtr, readPtr),
    true.B,
    0,
    WayLookupSize
  )
  XSPerfAccumulate("emptyWhenWrite", empty && io.write.head.fire)
  XSPerfAccumulate("emptyBypassOne", canBypassFirst && io.toMainPipe.fire)
  XSPerfAccumulate("emptyBypassTwo", canBypassSecond && io.toMainPipe.fire)
  XSPerfAccumulate("emptyNoBypass", empty && !(canBypassFirst || canBypassSecond))
  // exception stall cycles
  XSPerfAccumulate("waitingForExceptionRead", exceptionEntry.valid && !empty)
  XSPerfAccumulate("waitingForExceptionFlush", exceptionEntry.valid && empty)
  XSPerfAccumulate(
    "total_fetch",
    io.toMainPipe.fire
  )
  XSPerfAccumulate(
    "1fetch",
    io.toMainPipe.fire && !realTwoFetchValid
  )
  XSPerfAccumulate(
    "2fetch_raw",
    io.toMainPipe.fire && rawTwoFetchValid
  )
  XSPerfAccumulate(
    "2fetch_candidate",
    io.toMainPipe.fire && candidateTwoFetchValid
  )
  XSPerfAccumulate(
    "2fetch_real",
    io.toMainPipe.fire && realTwoFetchValid
  )
  XSPerfAccumulate(
    "2fetch_blocked",
    io.toMainPipe.fire && rawTwoFetchValid && !realTwoFetchValid
  )
  XSPerfSeqAccumulate(
    "2fetch_blocked",
    io.toMainPipe.fire && rawTwoFetchValid && !realTwoFetchValid,
    Seq(
      ("by_waylookup_unavailable", !candidateTwoFetchValid),
      ("by_sram_conflict", isDataSramReadConflict),
      ("by_mmio", hasMmio),
      ("by_itlb_exception", hasItlbException),
      ("by_backend_exception", hasBackendException)
    ),
    withPriority = true
  )
}
