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
import utility.XSPerfAccumulate
import xiangshan.frontend.bpu.SaturateCounter

// One fully-associative victim cache per (alignBank, internalBank); all lookups are done by MainBtbAlignBank
class MainBtbVictimCache(implicit p: Parameters) extends MainBtbModule {
  require(HasVC, "MainBtbVictimCache instantiated without VC enabled")
  require(isPow2(VCSize) && VCSize >= 4, "VCSize must be a power of 2 and at least 4")

  class MainBtbVictimCacheIO extends Bundle {
    // all entries, read combinationally by MainBtbAlignBank (S1 lookup, T1 CAM, predecode invalidation)
    val entries: Vec[VCEntry] = Output(Vec(VCSize, new VCEntry))

    // T1 insert: SRAM entry evicted by allocation, index chosen here (existing copy or replacer victim)
    class InsertReq extends Bundle {
      val entry: VCEntry = new VCEntry
    }
    val insert: Valid[InsertReq] = Flipped(Valid(new InsertReq))

    // T1 update: mispredicted branch found in VC, rewrite in place
    class UpdateReq extends Bundle {
      val idx:   UInt    = UInt(VCIdxLen.W)
      val entry: VCEntry = new VCEntry
    }
    val update: Valid[UpdateReq] = Flipped(Valid(new UpdateReq))

    // T1 counter training for every resolved conditional branch matching an entry
    val counterUpdate: Vec[Valid[SaturateCounter]] = Vec(VCSize, Flipped(Valid(TakenCounter())))

    // invalidation mask, OR of: T1 SRAM-hit duplicate, S2 duplicate, predecode ghost entry
    val invalidateMask: UInt = Input(UInt(VCSize.W))

    // S3 PLRU prediction touches (one per VC result slot)
    val predTouch: Vec[Valid[UInt]] = Vec(NumVCResultSlots, Flipped(Valid(UInt(VCIdxLen.W))))
  }

  val io: MainBtbVictimCacheIO = IO(new MainBtbVictimCacheIO)

  /* *** storage *** */
  private val entries = RegInit(VecInit(Seq.fill(VCSize)(0.U.asTypeOf(new VCEntry))))
  io.entries := entries

  /* *** replacer *** */
  private val replacer = Module(new MainBtbVCReplacer)
  replacer.io.validBits := VecInit(entries.map(_.valid)).asUInt
  replacer.io.predTouch := io.predTouch

  /* *** insert index: overwrite an existing copy of the same branch, else the replacer victim *** */
  private val insertEntry    = io.insert.bits.entry
  private val duplicateMatch = VecInit(entries.map { e =>
    e.valid && e.vcTag === insertEntry.vcTag && e.position === insertEntry.position
  })
  private val hasDuplicate = duplicateMatch.asUInt.orR
  private val duplicateIdx = PriorityEncoder(duplicateMatch.asUInt)
  private val insertIdx    = Mux(hasDuplicate, duplicateIdx, replacer.io.victim)

  replacer.io.trainTouch.valid := io.insert.valid || io.update.valid
  replacer.io.trainTouch.bits  := Mux(io.insert.valid, insertIdx, io.update.bits.idx)

  /* *** sequential writes, later blocks win: counter < invalidate < update < insert *** */
  io.counterUpdate.zipWithIndex.foreach { case (c, i) =>
    when(c.valid) {
      entries(i).counter := c.bits
    }
  }
  entries.zipWithIndex.foreach { case (e, i) =>
    when(io.invalidateMask(i)) {
      e.valid := false.B
    }
  }
  when(io.update.valid) {
    entries(io.update.bits.idx) := io.update.bits.entry
  }
  when(io.insert.valid) {
    entries(insertIdx) := io.insert.bits.entry
  }

  /* *** performance counters *** */
  XSPerfAccumulate("vc_insert", io.insert.valid)
  XSPerfAccumulate("vc_insert_duplicate", io.insert.valid && hasDuplicate)
  XSPerfAccumulate("vc_replace_invalid", io.insert.valid && !hasDuplicate && !replacer.io.validBits.andR)
  XSPerfAccumulate("vc_replace_plru", io.insert.valid && !hasDuplicate && replacer.io.validBits.andR)
  XSPerfAccumulate("vc_update", io.update.valid)
  XSPerfAccumulate("vc_counter_update", PopCount(io.counterUpdate.map(_.valid)))
  XSPerfAccumulate("vc_invalidate", PopCount(io.invalidateMask))
}
