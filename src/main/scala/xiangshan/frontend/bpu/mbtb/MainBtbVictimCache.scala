// Copyright (c) 2024-2025 Beijing Institute of Open Source Chip (BOSC)
// Copyright (c) 2020-2025 Institute of Computing Technology, Chinese Academy of Sciences
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

package xiangshan.frontend.bpu.mbtb

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import utility.ParallelPriorityEncoder
import utility.XSPerfAccumulate

class MainBtbVictimCache(implicit p: Parameters) extends MainBtbModule with Helpers {
  require(HasVC, "MainBtbVictimCache instantiated without VC enabled")

  class MainBtbVictimCacheIO extends Bundle {
    // Combinational lookup (per AlignBank, driven at S1 in MainBtb)
    class LookupReq extends Bundle {
      val vcTag:              UInt = UInt(VCTagWidth.W)
      val alignedInstOffset:  UInt = UInt(CfiAlignedPositionWidth.W)
      val crossPage:          Bool = Bool()
    }
    class LookupResp extends Bundle {
      val hit1:   Bool    = Bool()
      val vcIdx1: UInt    = UInt(VCIdxLen.W)
      val entry1: VCEntry = new VCEntry
      val hit2:   Bool    = Bool()
      val vcIdx2: UInt    = UInt(VCIdxLen.W)
      val entry2: VCEntry = new VCEntry
    }
    val lookup: Vec[LookupBundle] = Vec(NumAlignBanks, new LookupBundle)
    class LookupBundle extends Bundle {
      val req:  LookupReq  = Input(new LookupReq)
      val resp: LookupResp = Output(new LookupResp)
    }

    // T1 combinational read by index (one per VC result slot)
    class T1ReadBundle extends Bundle {
      val idx:   UInt    = Input(UInt(VCIdxLen.W))
      val entry: VCEntry = Output(new VCEntry)
    }
    val t1Read: Vec[T1ReadBundle] = Vec(NumVCResultSlots, new T1ReadBundle)

    // T1 insert: both-miss → evicted SRAM entry into VC
    class InsertReq extends Bundle {
      val entry: VCEntry = new VCEntry
    }
    val insert: Valid[InsertReq] = Flipped(Valid(new InsertReq))

    // T1 update: VC hit → update entry in-place
    class UpdateReq extends Bundle {
      val idx:   UInt    = UInt(VCIdxLen.W)
      val entry: VCEntry = new VCEntry
    }
    val update: Valid[UpdateReq] = Flipped(Valid(new UpdateReq))

    // T1 invalidate: SRAM hit → remove VC duplicate
    class InvalidateReq extends Bundle {
      val vcTag:    UInt = UInt(VCTagWidth.W)
      val position: UInt = UInt(CfiAlignedPositionWidth.W)
    }
    val invalidate: Valid[InvalidateReq] = Flipped(Valid(new InvalidateReq))

    // T1 PLRU training touches for actually-taken VC slots (one per VC result slot)
    val takenTouch: Vec[Valid[UInt]] = Vec(NumVCResultSlots, Flipped(Valid(UInt(VCIdxLen.W))))

    // all entries, so MainBtb can match every resolved conditional branch against the victim cache at T1
    val entries: Vec[VCEntry] = Output(Vec(VCSize, new VCEntry))
    // T1 direction training (always-taken bit + counter) per entry, same rule as the SRAM counters
    val directionUpdate: Vec[Valid[MainBtbDirectionEntry]] = Vec(VCSize, Flipped(Valid(new MainBtbDirectionEntry)))

    // Predecode-triggered VC entry invalidation (ghost entry removal)
    val pdInvalidate: Valid[InvalidateReq] = Flipped(Valid(new InvalidateReq))
  }

  val io: MainBtbVictimCacheIO = IO(new MainBtbVictimCacheIO)

  /* *** storage *** */
  private val entries = RegInit(VecInit(Seq.fill(VCSize)(0.U.asTypeOf(new VCEntry))))
  io.entries := entries

  /* *** replacer *** */
  private val replacer = Module(new MainBtbVCReplacer)
  replacer.io.validBits := VecInit(entries.map(_.valid)).asUInt
  replacer.io.takenTouch := io.takenTouch

  /* *** combinational lookup (driven at S1) — top-2 hits *** */
  io.lookup.foreach { bank =>
    val req = bank.req

    // Per-entry: vcTag match, valid, position >= offset, not crossPage
    val hitVec = VecInit(entries.map { e =>
      e.valid && e.vcTag === req.vcTag && e.position >= req.alignedInstOffset && !req.crossPage
    })

    // Split hitVec into two halves; each half independently produces one result slot.
    // Removes 1st↔2nd serial dependency and halves per-encoder width.
    val hitBits  = hitVec.asUInt
    val halfSize = VCSize / 2
    val hitLo    = hitBits(halfSize - 1, 0)       // entries 0..halfSize-1
    val hitHi    = hitBits(VCSize - 1, halfSize)  // entries halfSize..VCSize-1

    // ParallelPriorityEncoder: balanced-tree, log2(halfSize) depth
    val loInnerIdx = ParallelPriorityEncoder(hitLo)
    val hiInnerIdx = ParallelPriorityEncoder(hitHi)

    val loHit = hitLo.orR
    val hiHit = hitHi.orR

    val loIdx = Cat(0.U(1.W), loInnerIdx)         // VCIdxLen bits, upper bit = 0
    val hiIdx = Cat(1.U(1.W), hiInnerIdx)         // VCIdxLen bits, upper bit = 1

    // Half-size indexed entry selection (log2(halfSize) mux depth)
    val loEntries = VecInit(entries.take(halfSize))
    val hiEntries = VecInit(entries.drop(halfSize))

    // Normalize semantics so that (hit2 ⟹ hit1) holds, matching redistribution expectations:
    //   hit1 = any-hit (primary);  hit2 = both halves hit (secondary)
    // If only upper half hits, promote hi-half result into hit1 slot.
    val loEntry = loEntries(loInnerIdx)
    val hiEntry = hiEntries(hiInnerIdx)

    bank.resp.hit1   := loHit || hiHit
    bank.resp.vcIdx1 := Mux(loHit, loIdx, hiIdx)
    bank.resp.entry1 := Mux(loHit, loEntry, hiEntry)
    bank.resp.hit2   := loHit && hiHit
    bank.resp.vcIdx2 := hiIdx
    bank.resp.entry2 := hiEntry
  }

  /* *** T1 read (one per VC result slot) *** */
  io.t1Read.foreach { port =>
    port.entry := entries(port.idx)
  }

  /* *** T1 insert: both-miss *** */
  // Duplicate check: if vcTag + position already exists, overwrite that slot
  private val insertEntry    = io.insert.bits.entry
  private val duplicateMatch = VecInit(entries.map { e =>
    e.valid && e.vcTag === insertEntry.vcTag && e.position === insertEntry.position
  })
  private val hasDuplicate = duplicateMatch.asUInt.orR
  private val duplicateIdx = PriorityEncoder(duplicateMatch.asUInt)
  private val insertIdx    = Mux(hasDuplicate, duplicateIdx, replacer.io.victim)

  // Train touch: fires for both insert and update
  private val trainTouchValid = io.insert.valid || io.update.valid
  private val trainTouchIdx   = Mux(io.insert.valid, insertIdx, io.update.bits.idx)
  replacer.io.trainTouch.valid := trainTouchValid
  replacer.io.trainTouch.bits  := trainTouchIdx

  /* *** sequential writes, later blocks win: direction < invalidate < update < insert *** */
  io.directionUpdate.zipWithIndex.foreach { case (d, i) =>
    when(d.valid) {
      entries(i).alwaysTaken := d.bits.alwaysTaken
      entries(i).counter     := d.bits.counter
    }
  }
  when(io.invalidate.valid) {
    entries.foreach { e =>
      when(e.valid && e.vcTag === io.invalidate.bits.vcTag &&
        e.position === io.invalidate.bits.position) {
        e.valid := false.B
      }
    }
  }
  when(io.update.valid) {
    entries(io.update.bits.idx) := io.update.bits.entry
  }
  when(io.insert.valid) {
    entries(insertIdx) := io.insert.bits.entry
  }
  when(io.pdInvalidate.valid) {
    entries.foreach { e =>
      when(e.valid && e.vcTag === io.pdInvalidate.bits.vcTag &&
        e.position === io.pdInvalidate.bits.position) {
        e.valid := false.B
      }
    }
  }

  /* *** performance counters *** */
  private val perf_anyLookupHit = io.lookup.map(_.resp.hit1).reduce(_ || _)
  private val perf_anyLookupHit2 = io.lookup.map(_.resp.hit2).reduce(_ || _)
  XSPerfAccumulate("vc_lookup_hit", perf_anyLookupHit)
  XSPerfAccumulate("vc_lookup_hit2", perf_anyLookupHit2)
  XSPerfAccumulate("vc_insert", io.insert.valid)
  XSPerfAccumulate("vc_update", io.update.valid)
  XSPerfAccumulate("vc_invalidate", io.invalidate.valid)
  XSPerfAccumulate("vc_replace_invalid", io.insert.valid && !replacer.io.validBits.andR)
  XSPerfAccumulate("vc_replace_plru", io.insert.valid && replacer.io.validBits.andR)
  XSPerfAccumulate("vc_insert_duplicate", io.insert.valid && hasDuplicate)
  XSPerfAccumulate("vc_direction_update", PopCount(io.directionUpdate.map(_.valid)))
  XSPerfAccumulate("vc_pd_invalidate", io.pdInvalidate.valid)
}
