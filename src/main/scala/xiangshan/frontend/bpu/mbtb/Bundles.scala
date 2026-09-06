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
import xiangshan.XSCoreParamsKey
import xiangshan.frontend.PrunedAddr
import xiangshan.frontend.bpu.BranchAttribute
import xiangshan.frontend.bpu.BranchInfo
import xiangshan.frontend.bpu.SaturateCounter
import xiangshan.frontend.bpu.SaturateCounterFactory
import xiangshan.frontend.bpu.TargetCarry
import xiangshan.frontend.bpu.WriteReqBundle

object TakenCounter extends SaturateCounterFactory {
  def width(implicit p: Parameters): Int =
    p(XSCoreParamsKey).frontendParameters.bpuParameters.mbtbParameters.TakenCntWidth
}

class MainBtbEntry(implicit p: Parameters) extends MainBtbBundle {
  // whether the entry is valid
  val valid: Bool = Bool()

  val tag:       UInt            = UInt(TagWidth.W)
  val attribute: BranchAttribute = new BranchAttribute

  // Whether a branch is bias toward a single target
  // For conditional branch, this means bias toward same direction
  // For indirect branch, this means bias toward single target
//  val stronglyBiased: Bool = Bool() // TODO

  // Relative position to the aligned start addr
  val position: UInt = UInt(CfiAlignedPositionWidth.W)

  //  Branch target info
  val targetCarry:     TargetCarry = new TargetCarry
  val targetLowerBits: UInt        = UInt(TargetWidth.W)

//  val replaceCnt: UInt = UInt(2.W) // TODO: not used for now
}

class MainBtbEntrySramWriteReq(implicit p: Parameters) extends WriteReqBundle with HasMainBtbParameters {
  val setIdx:       UInt         = UInt(SetIdxLen.W)
  val entry:        MainBtbEntry = new MainBtbEntry
  override def tag: Option[UInt] = Some(Cat(entry.tag, entry.position)) // use entry's tag directly
}

// Direction state kept in the counter SRAM: a conditional branch starts always-taken and the bit is cleared for good
// on its first not-taken resolve; the counter is frozen while the bit is set (gem5 BTBEntry.alwaysTaken semantics)
class MainBtbDirectionEntry(implicit p: Parameters) extends MainBtbBundle {
  val alwaysTaken: Bool            = Bool()
  val counter:     SaturateCounter = TakenCounter()
}

object MainBtbDirectionEntry {
  // fresh state of a newly written entry: always-taken only for a conditional branch seen taken
  def init(alwaysTaken: Bool)(implicit p: Parameters): MainBtbDirectionEntry = {
    val e = Wire(new MainBtbDirectionEntry)
    e.alwaysTaken := alwaysTaken
    e.counter     := TakenCounter.WeakPositive
    e
  }
}

class MainBtbCounterSramWriteReq(implicit p: Parameters) extends MainBtbBundle {
  val setIdx:   UInt                 = UInt(SetIdxLen.W)
  val wayMask:  UInt                 = UInt(NumWay.W)
  val counters: Vec[MainBtbDirectionEntry] = Vec(NumWay, new MainBtbDirectionEntry)
}

class MainBtbMetaEntry(implicit p: Parameters) extends MainBtbBundle {
  val rawHit:      Bool            = Bool()
  val position:    UInt            = UInt(CfiPositionWidth.W)
  val attribute:   BranchAttribute = new BranchAttribute
  val counter:     SaturateCounter = TakenCounter()
  val alwaysTaken: Bool            = Bool()

  // VC: SRAM snapshot fields needed to reconstruct evicted entries at T1 (Path C)
  val sramValid:       Option[Bool]        = Option.when(HasVC)(Bool())
  val sramTag:         Option[UInt]        = Option.when(HasVC)(UInt(TagWidth.W))
  val targetLowerBits: Option[UInt]        = Option.when(HasVC)(UInt(TargetWidth.W))
  val targetCarry:     Option[TargetCarry] = Option.when(HasVC)(new TargetCarry)

  def hit(branch: BranchInfo): Bool = rawHit && position === branch.cfiPosition
}

// VC entry stored in the victim cache register file
class VCEntry(implicit p: Parameters) extends MainBtbBundle {
  val valid:           Bool            = Bool()
  val vcTag:           UInt            = UInt(VCTagWidth.W)
  val position:        UInt            = UInt(CfiAlignedPositionWidth.W)
  val attribute:       BranchAttribute = new BranchAttribute
  val targetCarry:     TargetCarry     = new TargetCarry
  val targetLowerBits: UInt            = UInt(TargetWidth.W)
  val counter:         SaturateCounter = TakenCounter()
  val alwaysTaken:     Bool            = Bool()
}

// Per-AlignBank VC meta stored in FTQ (minimal)
class VCMetaEntry(implicit p: Parameters) extends MainBtbBundle {
  val hit:   Bool = Bool()
  val vcIdx: UInt = UInt(VCIdxLen.W)
}

// Per-AlignBank VC prediction info, internal to MainBtb (piped S2 -> S3)
// NOTE: Legacy type, retained for compilation but no longer used in the merge path.
class VCAlignBankPredInfo(implicit p: Parameters) extends MainBtbBundle {
  val hit:       Bool = Bool()
  val vcIdx:     UInt = UInt(VCIdxLen.W)
  val mergedWay: UInt = UInt(log2Ceil(NumWay).W)
}

// Per-VC-result-slot info, internal to MainBtb (piped S1 -> S2 -> S3)
class VCResultSlotInfo(implicit p: Parameters) extends MainBtbBundle {
  val hit:             Bool = Bool()
  val vcIdx:           UInt = UInt(VCIdxLen.W)
  val posHigherBits:   UInt = UInt(AlignBankIdxLen.W)
  val sourceAlignBank: UInt = UInt(AlignBankIdxLen.W)
}

class MainBtbMeta(implicit p: Parameters) extends MainBtbBundle {
  val entries: Vec[Vec[MainBtbMetaEntry]] = Vec(NumAlignBanks, Vec(NumWay, new MainBtbMetaEntry))
  val vc: Option[Vec[VCMetaEntry]] = Option.when(HasVC)(Vec(NumVCResultSlots, new VCMetaEntry))
  // VC slot metas in MainBtbMetaEntry form (for SC/TAGE training compatibility)
  val vcSlotMetas: Option[Vec[MainBtbMetaEntry]] =
    Option.when(HasVC)(Vec(NumVCResultSlots, new MainBtbMetaEntry))

  // SRAM + VC slot metas as a single flat Seq (for indexing by NumBtbResultEntries)
  def allMetaEntries: Seq[MainBtbMetaEntry] =
    vcSlotMetas.map(vc => entries.flatten ++ vc).getOrElse(entries.flatten)
}

class PdFlushReq(implicit p: Parameters) extends MainBtbBundle {
  val setIdx:          UInt = UInt(SetIdxLen.W)
  val internalBankIdx: UInt = UInt(InternalBankIdxLen.W)
  val wayMask:         UInt = UInt(NumWay.W)
}

class MainBtbAlignBankTrace(implicit p: Parameters) extends MainBtbBundle {
  val needWrite: Bool         = Bool()
  val setIdx:    UInt         = UInt(SetIdxLen.W)
  val bankIdx:   UInt         = UInt(log2Ceil(NumInternalBanks).W)
  val wayIdx:    UInt         = UInt(log2Ceil(NumWay).W)
  val entry:     MainBtbEntry = new MainBtbEntry
}

class MainBtbTrace(implicit p: Parameters) extends MainBtbBundle {

  val startPc:     PrunedAddr      = PrunedAddr(VAddrBits)
  val cfiPosition: UInt            = UInt(CfiPositionWidth.W)
  val attribute:   BranchAttribute = new BranchAttribute

  val setIdx:       UInt = UInt(SetIdxLen.W)
  val internalIdx:  UInt = UInt(InternalBankIdxLen.W)
  val alignBankIdx: UInt = UInt(AlignBankIdxLen.W)
  val wayIdx:       UInt = UInt(NumWay.W)
}
