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

package xiangshan.frontend.bpu.ubtb

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import utility.XSPerfAccumulate
import xiangshan.frontend.PrunedAddr
import xiangshan.frontend.bpu.BasePredictor
import xiangshan.frontend.bpu.BasePredictorIO
import xiangshan.frontend.bpu.BpuFastTrain
import xiangshan.frontend.bpu.BranchAttribute
import xiangshan.frontend.bpu.HasFastTrainIO
import xiangshan.frontend.bpu.Prediction

/** Micro-BTB.
 *
 *  Register-based, fully-associative BTB at BP1. With `EnableTwoTaken`,
 *  each entry can hold a (B, C) 2-taken pair in slot2 in addition to the
 *  single-taken slot1. Pair lookup is combinational (`pairPrediction`
 *  Option output) and gated by BPU top.
 */
class MicroBtb(implicit p: Parameters) extends BasePredictor with HasMicroBtbParameters with Helpers {
  class MicroBtbIO(implicit p: Parameters) extends BasePredictorIO with HasFastTrainIO {
    // predict
    val prediction: Valid[Prediction] = Output(Valid(new Prediction))
    // pair prediction, present only when EnableTwoTaken
    val pairPrediction: Option[Valid[MicroBtbPairOut]] =
      if (EnableTwoTaken) Option(Output(Valid(new MicroBtbPairOut))) else None
    // mispKill: invalidate a pair by its first-branch startPc (backend redirect to second slot)
    val mispKill: Option[Valid[PrunedAddr]] =
      if (EnableTwoTaken) Option(Input(Valid(PrunedAddr(VAddrBits)))) else None
    // redirect strobe: clears the pairPrev snapshot so no pair chains across a squash
    val redirectValid: Option[Bool] =
      if (EnableTwoTaken) Option(Input(Bool())) else None
  }

  val io: MicroBtbIO = IO(new MicroBtbIO)

  println(f"MicroBtb:")
  println(f"  Size(full-assoc): $NumEntries")
  println(f"  Use fast-train: $UseFastTrain")
  println(f"  Replacer: $Replacer")
  println(f"  EnableTwoTaken (pair): $EnableTwoTaken")
  println(f"  Address fields:")
  addrFields.show(indent = 4)

  io.resetDone  := true.B
  io.trainReady := true.B

  /* *** submodules *** */
  private val entries = RegInit(VecInit(Seq.fill(NumEntries)(0.U.asTypeOf(new MicroBtbEntry))))

  private val replacer = Module(new MicroBtbReplacer)
  replacer.io.usefulCnt := VecInit(entries.map(_.usefulCnt))

  /* *** predict stage 0 ***
   * - io.startPc timing might be bad, simply cache it
   */
  private val s0_fire = io.stageCtrl.s0_fire && io.enable

  private val s0_startPc = io.startPc

  /* *** predict stage 1 ***
   * - read entries
   * - check if it's hit
   * - generate prediction
   * - update replacer
   */
  private val s1_fire = io.stageCtrl.s1_fire && io.enable

  private val s1_startPc = RegEnable(s0_startPc, s0_fire)
  private val s1_tag     = getTag(s1_startPc)

  private val s1_hitOH = VecInit(entries.map(e => e.valid && e.tag === s1_tag)).asUInt
  assert(PopCount(s1_hitOH) <= 1.U, "MicroBtb s1_hitOH should be one-hot")
  private val s1_hit      = s1_hitOH.orR
  private val s1_hitIdx   = OHToUInt(s1_hitOH)
  private val s1_hitEntry = entries(s1_hitIdx)

  // we do always-taken prediction in ubtb
  io.prediction.valid            := s1_hit
  io.prediction.bits.taken       := s1_hit
  io.prediction.bits.cfiPosition := s1_hitEntry.slot1.position
  io.prediction.bits.target      := getFullTarget(s1_startPc, s1_hitEntry.slot1.target, s1_hitEntry.slot1.targetCarry)
  io.prediction.bits.attribute   := s1_hitEntry.slot1.attribute

  // pair lookup (combinational): second.target uses first.target as its block base.
  if (EnableTwoTaken) {
    val s1_isPair = s1_hitEntry.isPair.get && s1_hitEntry.slot2.valid
    val pairOut   = Wire(Valid(new MicroBtbPairOut))
    pairOut.valid             := s1_hit && s1_isPair
    pairOut.bits.isPair       := s1_isPair
    pairOut.bits.first        := io.prediction.bits
    pairOut.bits.confidence   := s1_hitEntry.slot2.confidence.getOrElse(0.U)
    pairOut.bits.second.taken := true.B // pair is always (taken, taken)
    pairOut.bits.second.cfiPosition := s1_hitEntry.slot2.position
    pairOut.bits.second.attribute   := s1_hitEntry.slot2.attribute
    pairOut.bits.second.target := getFullTarget(
      io.prediction.bits.target,
      s1_hitEntry.slot2.target,
      s1_hitEntry.slot2.targetCarry
    )
    io.pairPrediction.get := pairOut
  }

  // update replacer
  replacer.io.predTouch.valid := s1_hit && s1_fire
  replacer.io.predTouch.bits  := s1_hitIdx

  /* *** train stage 0 ***
   * - read entries
   * - check if hits entries
   * - check if hits t1 stage
   * - calculate hit flags
   */
  private val t0_fire        = Wire(Bool())
  private val t0_startPc     = Wire(PrunedAddr(VAddrBits))
  private val t0_actualTaken = Wire(Bool())
  private val t0_position    = Wire(UInt(CfiPositionWidth.W))
  private val t0_fullTarget  = Wire(PrunedAddr(VAddrBits))
  private val t0_attribute   = Wire(new BranchAttribute)

  if (UseFastTrain) {
    t0_fire        := io.fastTrain.get.valid && io.enable
    t0_startPc     := io.fastTrain.get.bits.startPc
    t0_actualTaken := io.fastTrain.get.bits.finalPrediction.taken
    t0_position    := io.fastTrain.get.bits.finalPrediction.cfiPosition
    t0_fullTarget  := io.fastTrain.get.bits.finalPrediction.target
    t0_attribute   := io.fastTrain.get.bits.finalPrediction.attribute
  } else {
    // FIXME: not sure if first mispredict is the best, maybe first taken?
    t0_fire        := io.stageCtrl.t0_fire && io.train.mispredictBranch.valid && io.enable
    t0_startPc     := io.train.startPc
    t0_actualTaken := io.train.mispredictBranch.bits.taken
    t0_position    := io.train.mispredictBranch.bits.cfiPosition
    t0_fullTarget  := io.train.mispredictBranch.bits.target
    t0_attribute   := io.train.mispredictBranch.bits.attribute
  }

  private val t0_tag         = getTag(t0_startPc)
  private val t0_target      = getEntryTarget(t0_fullTarget)
  private val t0_targetCarry = if (EnableTargetFix) Option(getTargetCarry(t0_startPc, t0_fullTarget)) else None

  private val t0_hitOH = VecInit(entries.map(e => e.valid && e.tag === t0_tag)).asUInt
  // t0 may hit t1, so we add a "real" prefix for entries hit
  private val t0_realHit    = t0_hitOH.orR
  private val t0_realHitIdx = OHToUInt(t0_hitOH)

  // If there are two contiguous trains, the first one is too late to be written to the entries,
  // the second train might be a false "not hit" and allocate a new entry, causing a multi-hit;
  // or, the first may replace the entry, causing a false "hit" in the second train, causing wrong update.
  // So, we define some of the t1 signals in advance, and use them to check if the contiguous trains are hit.
  private val t1_fire         = Wire(Bool())
  private val t1_tag          = Wire(UInt(TagWidth.W))
  private val t1_updateIdx    = Wire(UInt(log2Up(NumEntries).W))
  private val t1_hitEntry     = Wire(new MicroBtbEntry)
  private val t1_updatedEntry = WireDefault(t1_hitEntry) // will be updated in t1, then write back to entries
  private val t1_allocate     = Wire(Bool())

  // if t0_tag === t1_tag, t1 must be updating the entry, so we can see it as a hit, and use t1_updateIdx as hitIdx
  private val t0_hitT1Update = Wire(Bool())
  // if t0 hits but t1 is replacing it, we should see it as not hit
  private val t0_hitT1Victim = t1_fire && t0_realHitIdx === replacer.io.victim && t1_allocate

  // fix final hit
  private val t0_hit = t0_realHit && !t0_hitT1Victim || t0_hitT1Update
  // select hit entry: use t1_updatedEntry if t0_hitT1Update, otherwise use real hit entry
  private val t0_hitIdx   = Mux(t0_hitT1Update, t1_updateIdx, t0_realHitIdx)
  private val t0_hitEntry = Mux(t0_hitT1Update, t1_updatedEntry, entries(t0_realHitIdx))

  // calculate hit flags, valid only when t0_hit
  private val t0_hitNotUseful     = t0_hitEntry.usefulCnt.isSaturateNegative
  private val t0_hitPositionSame  = t0_hitEntry.slot1.position === t0_position
  private val t0_hitAttributeSame = t0_hitEntry.slot1.attribute === t0_attribute
  private val t0_hitTargetSame    = t0_hitEntry.slot1.target === t0_target

  /* *** train stage 1 ***
   * - select victim
   * - generate updated entry
   * - update entries
   * - update replacer
   */
  t1_fire := RegNext(t0_fire, false.B)
  t1_tag  := RegEnable(t0_tag, t0_fire)
  private val t1_actualTaken = RegEnable(t0_actualTaken, t0_fire)
  private val t1_position    = RegEnable(t0_position, t0_fire)
  private val t1_target      = RegEnable(t0_target, t0_fire)
  private val t1_attribute   = RegEnable(t0_attribute, t0_fire)
  private val t1_targetCarry = t0_targetCarry.map(w => RegEnable(w, t0_fire)) // if (EnableTargetFix)

  private val t1_hit    = RegEnable(t0_hit, t0_fire)
  private val t1_hitIdx = RegEnable(t0_hitIdx, t0_fire)
  t1_hitEntry := RegEnable(t0_hitEntry, t0_fire)

  // hit states (flags), valid only when t1_hit
  private val t1_hitNotUseful     = RegEnable(t0_hitNotUseful, t0_fire)
  private val t1_hitPositionSame  = RegEnable(t0_hitPositionSame, t0_fire)
  private val t1_hitAttributeSame = RegEnable(t0_hitAttributeSame, t0_fire)
  private val t1_hitTargetSame    = RegEnable(t0_hitTargetSame, t0_fire)
  // only when t1 is updating/allocating can t0 hit it
  t0_hitT1Update := t1_fire && t0_tag === t1_tag && (t1_hit || t1_allocate)
  // init a new entry
  private def initEntryIfNotUseful(notUseful: Bool): Unit =
    when(notUseful) {
      t1_updatedEntry.tag := t1_tag
      t1_updatedEntry.usefulCnt.resetSaturatePositive() // usefulCnt inits at strong positive, in/decrease by policy
      // slot1
      t1_updatedEntry.slot1.position       := t1_position
      t1_updatedEntry.slot1.attribute      := t1_attribute
      t1_updatedEntry.slot1.target         := t1_target
      t1_updatedEntry.slot1.isStaticTarget := true.B // inits at true, set to false when we see a different target
      t1_updatedEntry.slot1.targetCarry.foreach(_ := t1_targetCarry.get) // if (EnableTargetFix)
      // Pair is freshly invalidated on entry init; promotion may re-arm slot2 below.
      t1_updatedEntry.slot2.valid := false.B
      t1_updatedEntry.isPair.foreach(_ := false.B)
      t1_updatedEntry.slot2.confidence.foreach(_ := 0.U)
    }.otherwise {
      t1_updatedEntry.usefulCnt := t1_hitEntry.usefulCnt.getDecrease()
    }

  when(t1_fire) {
    when(!t1_hit) {
      // not hit
      // init a new entry if actually taken
      initEntryIfNotUseful(true.B)
    }.elsewhen(!t1_hitAttributeSame || !t1_hitPositionSame || !t1_hitTargetSame || !t1_actualTaken) {
      // hit, but attribute/position mismatch, or actually not taken
      // if already not useful and actually taken, init a new entry, otherwise decrease usefulCnt
      initEntryIfNotUseful(t1_hitNotUseful)
      // and, if we've seen a different target, mark target as not static
      when(!t1_hitTargetSame) {
        t1_updatedEntry.slot1.isStaticTarget := false.B
      }
      // Pair demotion: BR1 mismatch / not-taken invalidates any live pair.
      t1_updatedEntry.slot2.valid := false.B
      t1_updatedEntry.isPair.foreach(_ := false.B)
      t1_updatedEntry.slot2.confidence.foreach(_ := 0.U)
    }.otherwise {
      // everything matches, and actually taken
      // increase usefulCnt
      t1_updatedEntry.usefulCnt := t1_hitEntry.usefulCnt.getIncrease()
      // keep slot2 from the live reg (not the forwarded hit entry) so a prior-cycle
      // kill is not re-validated by this slot1-confirm write-back.
      if (EnableTwoTaken) {
        t1_updatedEntry.slot2  := entries(t1_hitIdx).slot2
        t1_updatedEntry.isPair.foreach(_ := entries(t1_hitIdx).isPair.get)
      }
    }
  }

  // pair learning: chain two consecutive fastTrain cycles (prev=A, cur=B) and
  // update prev's entry. snapshot cleared on redirect so no pair chains across a squash.
  private val pairPrev_valid = if (EnableTwoTaken) RegInit(false.B) else WireDefault(false.B)
  private val pairPrev_ft    = if (UseFastTrain && EnableTwoTaken)
    Reg(chiselTypeOf(io.fastTrain.get.bits)) else WireDefault(0.U.asTypeOf(new BpuFastTrain))
  if (UseFastTrain && EnableTwoTaken) {
    val ftFire = io.fastTrain.get.valid && io.enable
    when(io.redirectValid.get) {
      // drop the pre-redirect prev; do not latch this cycle's ft (flush in flight)
      pairPrev_valid := false.B
    }.elsewhen(ftFire) {
      // hold prev across fastTrain bubbles: the next beat is still the stream
      // successor, and the startPc==target check rejects non-adjacent chains.
      pairPrev_valid := true.B
      pairPrev_ft    := io.fastTrain.get.bits
    }
  }

  // sequential adjacency: prev taken into cur's start (self-loop excluded).
  private val t0_pairSeq =
    if (UseFastTrain && EnableTwoTaken) {
      val cur       = io.fastTrain.get
      val prev_pred = pairPrev_ft.finalPrediction
      pairPrev_valid && cur.valid && io.enable &&
        prev_pred.taken &&
        (cur.bits.startPc.toUInt === prev_pred.target.toUInt) &&
        (cur.bits.startPc.toUInt =/= pairPrev_ft.startPc.toUInt)
    } else false.B

  // slot A type gate: cond / direct-jmp only; reject call + return + indirect (matches the emit gate).
  private val t0_slotAOk =
    if (EnableTwoTaken) {
      val a = pairPrev_ft.finalPrediction.attribute
      !a.hasPush && !a.hasPop && !a.isIndirect
    } else false.B

  // locate slot A entry by tag(prev.startPc)
  private val t0_promoteTag      = if (EnableTwoTaken) getTag(pairPrev_ft.startPc) else 0.U
  private val t0_promoteHitOH    = if (EnableTwoTaken)
    VecInit(entries.map(e => e.valid && e.tag === t0_promoteTag)).asUInt else 0.U
  private val t0_promoteEntryHit = if (EnableTwoTaken) t0_promoteHitOH.orR else false.B
  private val t0_promoteHitIdx   = if (EnableTwoTaken) OHToUInt(t0_promoteHitOH) else 0.U

  // entry consistency (tag-aliasing guard): hit entry's slot1 must match prev.
  private val t0_entryConsistent =
    if (EnableTwoTaken) {
      val e         = entries(t0_promoteHitIdx)
      val prev_pred = pairPrev_ft.finalPrediction
      (e.slot1.position === prev_pred.cfiPosition) &&
      (e.slot1.target === getEntryTarget(prev_pred.target))
    } else false.B

  // cur (slot B candidate) classification
  private val t0_pairBase = t0_pairSeq && t0_slotAOk && t0_promoteEntryHit && t0_entryConsistent
  // fastTrainKill: B not-taken, return, call, or indirect -> invalidate slot2
  private val t0_fastTrainKill =
    if (UseFastTrain && EnableTwoTaken) {
      val cur_pred = io.fastTrain.get.bits.finalPrediction
      val cur_attr = cur_pred.attribute
      t0_pairBase && (!cur_pred.taken || cur_attr.hasPop || cur_attr.hasPush || cur_attr.isIndirect)
    } else false.B
  // eligible: B is direct-jmp or always-taken-proxy conditional -> alloc/confirm/kill
  private val t0_pairEligible =
    if (UseFastTrain && EnableTwoTaken) {
      val cur_pred = io.fastTrain.get.bits.finalPrediction
      val cur_attr = cur_pred.attribute
      t0_pairBase && cur_pred.taken && !cur_attr.hasPop && !cur_attr.hasPush && !cur_attr.isIndirect &&
        (cur_attr.isDirect || cur_attr.isConditional)
    } else false.B

  // latch eligible-promotion data into t1
  private val t1_promote     = RegNext(t0_pairEligible, init = false.B)
  private val t1_promoteIdx  = RegEnable(t0_promoteHitIdx, t0_pairEligible)
  private val t1_promoteSlot2Pos   =
    if (UseFastTrain && EnableTwoTaken)
      RegEnable(io.fastTrain.get.bits.finalPrediction.cfiPosition, t0_pairEligible) else 0.U
  private val t1_promoteSlot2Attr  =
    if (UseFastTrain && EnableTwoTaken)
      RegEnable(io.fastTrain.get.bits.finalPrediction.attribute, t0_pairEligible)
    else 0.U.asTypeOf(new BranchAttribute)
  private val t1_promoteSlot2Tgt   =
    if (UseFastTrain && EnableTwoTaken)
      RegEnable(getEntryTarget(io.fastTrain.get.bits.finalPrediction.target), t0_pairEligible) else 0.U
  private val t1_promoteSlot2Carry =
    if (UseFastTrain && EnableTwoTaken && EnableTargetFix)
      Some(RegEnable(getTargetCarry(io.fastTrain.get.bits.startPc, io.fastTrain.get.bits.finalPrediction.target),
        t0_pairEligible)) else None

  // latch kill data into t1
  private val t1_fastTrainKill    = RegNext(t0_fastTrainKill, init = false.B)
  private val t1_fastTrainKillIdx = RegEnable(t0_promoteHitIdx, t0_fastTrainKill)

  // select the entry: if hit, use the hit entry, otherwise use the victim from replacer (first not useful, or Plru)
  t1_allocate  := !t1_hit && t1_actualTaken
  t1_updateIdx := Mux(t1_hit, t1_hitIdx, replacer.io.victim)

  // mispKill (backend redirect to pair-second): locate the pair-first entry by tag
  private val t1_mispKillReq   = if (EnableTwoTaken) io.mispKill.get
                                 else 0.U.asTypeOf(Valid(PrunedAddr(VAddrBits)))
  private val t1_mispKillTag   = if (EnableTwoTaken) getTag(t1_mispKillReq.bits) else 0.U
  private val t1_mispKillHitOH = if (EnableTwoTaken)
    VecInit(entries.map(e => e.valid && e.tag === t1_mispKillTag)).asUInt else 0.U
  private val t1_mispKillHit   = if (EnableTwoTaken) (t1_mispKillReq.valid && t1_mispKillHitOH.orR) else false.B
  private val t1_mispKillIdx   = if (EnableTwoTaken) OHToUInt(t1_mispKillHitOH) else 0.U

  // and write back the updated entry
  when(t1_fire && (t1_hit || t1_allocate)) { // update entry if hit, or alloc entry only for taken branches
    entries(t1_updateIdx) := t1_updatedEntry
  }

  // pair eligible write-back (alloc / confirm / kill), after the generic update;
  // skipped when it targets the same entry idx as the generic update.
  if (EnableTwoTaken) {
    val t1_promoteConflict = t1_fire && (t1_hit || t1_allocate) && (t1_promoteIdx === t1_updateIdx)
    val target    = entries(t1_promoteIdx)
    val priorPair = target.isPair.map(_ && target.slot2.valid).getOrElse(false.B)
    val sameBR2   = priorPair &&
      (target.slot2.position === t1_promoteSlot2Pos) &&
      (target.slot2.attribute === t1_promoteSlot2Attr) &&
      (target.slot2.target === t1_promoteSlot2Tgt)
    val curConf = target.slot2.confidence.getOrElse(0.U)

    def writeSlot2Content(idx: UInt): Unit = {
      entries(idx).slot2.position  := t1_promoteSlot2Pos
      entries(idx).slot2.attribute := t1_promoteSlot2Attr
      entries(idx).slot2.target    := t1_promoteSlot2Tgt
      t1_promoteSlot2Carry.foreach { c =>
        entries(idx).slot2.targetCarry.foreach(_ := c)
      }
    }

    when(t1_promote && !t1_promoteConflict) {
      when(!priorPair) {
        // PairAlloc: no live pair -> install slot2, confidence 1
        entries(t1_promoteIdx).isPair.foreach(_ := true.B)
        entries(t1_promoteIdx).slot2.valid := true.B
        entries(t1_promoteIdx).slot2.taken := true.B
        writeSlot2Content(t1_promoteIdx)
        entries(t1_promoteIdx).slot2.confidence.foreach(_ := 1.U)
      }.elsewhen(sameBR2) {
        // PairConfirm: content matches -> saturating increment, keep slot2
        entries(t1_promoteIdx).slot2.valid := true.B
        entries(t1_promoteIdx).slot2.taken := true.B
        entries(t1_promoteIdx).slot2.confidence.foreach { c =>
          c := Mux(curConf === PairConfMax.U, curConf, curConf + 1.U)
        }
      }.otherwise {
        // PairKill: any content mismatch proves the pair unstable -> invalidate now;
        // a later clean pass re-allocs the new content (confidence counts consecutive
        // confirms, so the emit threshold means N clean passes in a row).
        entries(t1_promoteIdx).isPair.foreach(_ := false.B)
        entries(t1_promoteIdx).slot2.valid := false.B
        entries(t1_promoteIdx).slot2.confidence.foreach(_ := 0.U)
      }
    }

    // content-mismatch kills, split by position relation for diagnosis
    val t1_contentKill = t1_promote && !t1_promoteConflict && priorPair && !sameBR2
    XSPerfAccumulate("pairContentKill", t1_contentKill)
    XSPerfAccumulate("pairContentKillPosEarlier",
      t1_contentKill && t1_promoteSlot2Pos < target.slot2.position)
    XSPerfAccumulate("pairContentKillPosLater",
      t1_contentKill && t1_promoteSlot2Pos > target.slot2.position)
    XSPerfAccumulate("pairContentKillTgtOnly",
      t1_contentKill && t1_promoteSlot2Pos === target.slot2.position)

    // fastTrainKill: invalidate slot2 of the prev entry on ineligible B. Runs after
    // the generic write so it wins by last-connect even on idx aliasing.
    when(t1_fastTrainKill) {
      entries(t1_fastTrainKillIdx).isPair.foreach(_ := false.B)
      entries(t1_fastTrainKillIdx).slot2.valid := false.B
      entries(t1_fastTrainKillIdx).slot2.confidence.foreach(_ := 0.U)
    }
  }

  // mispKill: invalidate the matching slot2. Runs after the generic write so it
  // wins by last-connect even on idx aliasing.
  if (EnableTwoTaken) {
    when(t1_mispKillHit) {
      entries(t1_mispKillIdx).isPair.foreach(_ := false.B)
      entries(t1_mispKillIdx).slot2.valid := false.B
      entries(t1_mispKillIdx).slot2.confidence.foreach(_ := 0.U)
    }
    XSPerfAccumulate("pairSecondMispHit",  t1_mispKillHit)
    XSPerfAccumulate("pairSecondMispMiss", t1_mispKillReq.valid && !t1_mispKillHitOH.orR)
  }

  // update replacer
  replacer.io.trainTouch.valid := t1_fire
  replacer.io.trainTouch.bits  := t1_updateIdx

  /* *** perf *** */
  XSPerfAccumulate("predHit", s1_hit && s1_fire)
  XSPerfAccumulate("predMiss", !s1_hit && s1_fire)

  XSPerfAccumulate("s1Hits3FallThrough", t1_fire && t1_hit && !t1_actualTaken)
  XSPerfAccumulate("s1Misses3Taken", t1_fire && !t1_hit && t1_actualTaken)
  XSPerfAccumulate("s1Hits3Taken", t1_fire && t1_hit && t1_actualTaken)
  XSPerfAccumulate("s1Misses3FallThrough", t1_fire && !t1_hit && !t1_actualTaken)

  XSPerfAccumulate("s1InvalidatedEntries", t1_fire && t1_hit && !t1_actualTaken && t1_hitNotUseful)

  XSPerfAccumulate("trainHitEntries", t0_fire && t0_realHit)
  XSPerfAccumulate("trainHitT1Update", t0_fire && t0_hitT1Update)
  XSPerfAccumulate("trainHitT1Victim", t0_fire && t0_hitT1Victim)

  XSPerfAccumulate("allocateNotUseful", t1_fire && t1_allocate && replacer.io.perf.replaceNotUseful)
  XSPerfAccumulate("allocatePlru", t1_fire && t1_allocate && !replacer.io.perf.replaceNotUseful)
  XSPerfAccumulate(
    "replace",
    t1_fire && t1_hit && (
      !t1_hitAttributeSame && t1_hitNotUseful ||
        t1_hitAttributeSame && !t1_hitPositionSame && t1_hitNotUseful
    )
  )

  // pair perf counters
  if (EnableTwoTaken) {
    val s1_pairValid = io.pairPrediction.get.valid
    XSPerfAccumulate("pairLookupHit", s1_pairValid && s1_fire)
    XSPerfAccumulate(
      "pairLookupHitAtThreshold",
      s1_pairValid && s1_fire &&
        (io.pairPrediction.get.bits.confidence >=
          Mux(
            io.pairPrediction.get.bits.second.attribute.isConditional,
            PairCondConfThreshold.U,
            PairDirectConfThreshold.U
          ))
    )

    // chain detection + classification (fastTrain dependent)
    if (UseFastTrain) {
      val cur_attr = io.fastTrain.get.bits.finalPrediction.attribute
      val cur_taken = io.fastTrain.get.bits.finalPrediction.taken
      XSPerfAccumulate("pairChainFound", t0_pairSeq)
      // chains recovered by holding prev across a fastTrain bubble
      XSPerfAccumulate("pairChainAcrossBubble",
        t0_pairSeq && !RegNext(io.fastTrain.get.valid && io.enable, false.B))
      XSPerfAccumulate("pairChainBroken",
        pairPrev_valid && io.fastTrain.get.valid && io.enable &&
          pairPrev_ft.finalPrediction.taken && !t0_pairSeq)
      XSPerfAccumulate("pairSkipEntryMismatch",
        t0_pairSeq && t0_slotAOk && t0_promoteEntryHit && !t0_entryConsistent)
      XSPerfAccumulate("pairSkipFirstRet", t0_pairSeq && pairPrev_ft.finalPrediction.attribute.hasPop)
      XSPerfAccumulate("pairSkipFirstIndirect",
        t0_pairSeq && !pairPrev_ft.finalPrediction.attribute.hasPop &&
          pairPrev_ft.finalPrediction.attribute.isIndirect)
      // chains lost to the call-A reject; gauges whether exact post-push second-slot meta is worth building
      XSPerfAccumulate("pairSkipFirstCall",
        t0_pairSeq && !pairPrev_ft.finalPrediction.attribute.hasPop &&
          !pairPrev_ft.finalPrediction.attribute.isIndirect &&
          pairPrev_ft.finalPrediction.attribute.hasPush)
      XSPerfAccumulate("pairFastTrainKill", t0_fastTrainKill)
      XSPerfAccumulate("pairFastTrainKillNoTaken", t0_fastTrainKill && !cur_taken)
      XSPerfAccumulate("pairFastTrainKillRet", t0_fastTrainKill && cur_taken && cur_attr.hasPop)
      XSPerfAccumulate("pairFastTrainKillCall",
        t0_fastTrainKill && cur_taken && !cur_attr.hasPop && cur_attr.hasPush)
      XSPerfAccumulate("pairFastTrainKillIndirect",
        t0_fastTrainKill && cur_taken && !cur_attr.hasPop && !cur_attr.hasPush && cur_attr.isIndirect)
      XSPerfAccumulate("pairEligible", t0_pairEligible)
    }

    // write-back outcomes
    XSPerfAccumulate("pairPromote", t1_promote)
    XSPerfAccumulate("pairPromoteConflict",
      t1_promote && t1_fire && (t1_hit || t1_allocate) && (t1_promoteIdx === t1_updateIdx))
    XSPerfAccumulate("pairFastTrainKillApplied", t1_fastTrainKill)

    // firstKill: cur's own pair invalidated because slot1 (first branch) changed
    XSPerfAccumulate("pairFirstKillNotTaken",
      t1_fire && t1_hit && !t1_actualTaken && t1_hitEntry.isPair.get)
    XSPerfAccumulate("pairFirstKillMismatch",
      t1_fire && t1_hit && t1_actualTaken &&
        (!t1_hitAttributeSame || !t1_hitPositionSame || !t1_hitTargetSame) && t1_hitEntry.isPair.get)
  }
}
