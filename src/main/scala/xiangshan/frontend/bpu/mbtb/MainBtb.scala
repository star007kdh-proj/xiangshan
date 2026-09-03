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
import utility.ChiselDB
import utility.XSPerfAccumulate
import utility.XSPerfHistogram
import utils.VecRotate
import xiangshan.frontend.bpu.BasePredictor
import xiangshan.frontend.bpu.BasePredictorIO
import xiangshan.frontend.bpu.PdInvalidateReq
import xiangshan.frontend.bpu.Prediction

class MainBtb(implicit p: Parameters) extends BasePredictor with HasMainBtbParameters with Helpers {
  class MainBtbIO(implicit p: Parameters) extends BasePredictorIO {
    // prediction specific bundle
    val result: Vec[Valid[Prediction]] = Output(Vec(NumBtbPredEntries, Valid(new Prediction)))
    val meta:   MainBtbMeta            = Output(new MainBtbMeta)

    // timing optimization: send positions earlier to TAGE
    val s1_positions: Vec[UInt] = Output(Vec(NumBtbPredEntries, UInt(CfiPositionWidth.W)))

    // final s3_takenMask (mbtb + tage + sc), used to touch replacer accurately
    val s3_takenMask: Vec[Bool] = Input(Vec(NumBtbPredEntries, Bool()))

    // predecode-triggered ghost entry invalidation
    val pdInvalidate: Valid[PdInvalidateReq] = Flipped(Valid(new PdInvalidateReq))
  }

  val io: MainBtbIO = IO(new MainBtbIO)

  // print params
  println(f"MainBtb:")
  println(f"  Size(set, way, align, internal): $NumSets * $NumWay * $NumAlignBanks * $NumInternalBanks = $NumEntries")
  println(f"  Address fields:")
  addrFields.show(indent = 4)
  if (HasVC) {
    println(f"  VC: $VCSize entries * ${NumAlignBanks * NumInternalBanks} instances, " +
      f"tag=$VCTagWidth bits, resultSlots=$NumVCResultSlots")
  }

  /* *** submodules *** */
  private val alignBanks = Seq.tabulate(NumAlignBanks)(alignIdx => Module(new MainBtbAlignBank(alignIdx)))

  io.resetDone := alignBanks.map(_.io.resetDone).reduce(_ && _)

  io.trainReady := true.B

  private val s0_fire, s1_fire, s2_fire, s3_fire = Wire(Bool())
  alignBanks.foreach { b =>
    b.io.stageCtrl.s0_fire := s0_fire
    b.io.stageCtrl.s1_fire := s1_fire
    b.io.stageCtrl.s2_fire := s2_fire
    b.io.stageCtrl.s3_fire := s3_fire
    // alignBank does not care t0, it's using t1 only
    b.io.stageCtrl.t0_fire := false.B
  }

  /* *** s0 ***
   * calculate per-bank startPc and posHigherBits
   * send read request to alignBanks
   */
  s0_fire := io.stageCtrl.s0_fire && io.enable
  private val s0_startPc = io.startPc
  // rotate read addresses according to the first align bank index
  private val s0_rotator = VecRotate(getAlignBankIndex(s0_startPc))
  private val s0_startPcVec = s0_rotator.rotate(
    VecInit.tabulate(NumAlignBanks) { i =>
      if (i == 0)
        s0_startPc // keep lower bits for the first one
      else
        getAlignedPc(s0_startPc + (i << FetchBlockAlignWidth).U) // use aligned for others
    }
  )
  private val s0_posHigherBitsVec = s0_rotator.rotate(VecInit.tabulate(NumAlignBanks)(_.U(AlignBankIdxLen.W)))

  alignBanks.zipWithIndex.foreach { case (b, i) =>
    b.io.read.req.startPc       := s0_startPcVec(i)
    b.io.read.req.posHigherBits := s0_posHigherBitsVec(i)
    b.io.read.req.crossPage     := isCrossPage(s0_startPcVec(i), s0_startPc)
  }

  // VC address pipes: S0 -> S1 -> S2, used to compute VC slot targets and positions
  private val s1_startPcVec_opt       = Option.when(HasVC)(RegEnable(s0_startPcVec, s0_fire))
  private val s1_posHigherBitsVec_opt = Option.when(HasVC)(RegEnable(s0_posHigherBitsVec, s0_fire))

  /* *** s1 ***
   * wait alignBanks
   * assign VC hits (looked up inside alignBanks) to VC result slots, expose positions for TAGE tag computation
   */
  s1_fire := io.stageCtrl.s1_fire && io.enable

  // SRAM positions (0..NumWay*NumAlignBanks-1)
  io.s1_positions := VecInit(
    alignBanks.flatMap(_.io.read.s1_positions) ++
      Seq.fill(NumVCResultSlots)(0.U(CfiPositionWidth.W))
  )

  private val s1_vcSlotInfos_opt   = Option.when(HasVC)(Wire(Vec(NumVCResultSlots, new VCResultSlotInfo)))
  private val s1_vcSlotEntries_opt = Option.when(HasVC)(Wire(Vec(NumVCResultSlots, new VCEntry)))

  if (HasVC) {
    val posHigherBitsVec = s1_posHigherBitsVec_opt.get
    val slotInfos        = s1_vcSlotInfos_opt.get
    val slotEntries      = s1_vcSlotEntries_opt.get

    require(NumAlignBanks == 2, "Redistribution logic assumes NumAlignBanks == 2")
    val resp0 = alignBanks(0).io.read.s1_vc.get
    val resp1 = alignBanks(1).io.read.s1_vc.get

    // Per-slot redistribution selectors (mutually exclusive by construction
    // -> independent 2:1 muxes instead of priority chain):
    //   redistToSlot0: AB0 miss & AB1 has both hits -> slot 0 borrows AB1's 2nd hit
    //   redistToSlot1: AB1 miss & AB0 has both hits -> slot 1 borrows AB0's 2nd hit
    val redistToSlot0 = !resp0.hit1 && resp1.hit1 && resp1.hit2
    val redistToSlot1 = !resp1.hit1 && resp0.hit1 && resp0.hit2

    // Slot 0: default = (AB0, 1st hit); redist = (AB1, 2nd hit)
    slotInfos(0).hit             := resp0.hit1 || redistToSlot0
    slotInfos(0).vcIdx           := Mux(redistToSlot0, resp1.vcIdx2, resp0.vcIdx1)
    slotInfos(0).posHigherBits   := Mux(redistToSlot0, posHigherBitsVec(1), posHigherBitsVec(0))
    slotInfos(0).sourceAlignBank := Mux(redistToSlot0, 1.U, 0.U)
    slotEntries(0)               := Mux(redistToSlot0, resp1.entry2, resp0.entry1)

    // Slot 1 (symmetric): default = (AB1, 1st hit); redist = (AB0, 2nd hit)
    slotInfos(1).hit             := resp1.hit1 || redistToSlot1
    slotInfos(1).vcIdx           := Mux(redistToSlot1, resp0.vcIdx2, resp1.vcIdx1)
    slotInfos(1).posHigherBits   := Mux(redistToSlot1, posHigherBitsVec(0), posHigherBitsVec(1))
    slotInfos(1).sourceAlignBank := Mux(redistToSlot1, 0.U, 1.U)
    slotEntries(1)               := Mux(redistToSlot1, resp0.entry2, resp1.entry1)

    // Write VC slot positions into s1_positions for TAGE benefit
    for (s <- 0 until NumVCResultSlots) {
      when(slotInfos(s).hit) {
        io.s1_positions(NumWay * NumAlignBanks + s) :=
          Cat(slotInfos(s).posHigherBits, slotEntries(s).position)
      }
    }
  }

  // Pipe VC slot results: S1 -> S2
  private val s2_vcSlotInfos_opt      = s1_vcSlotInfos_opt.map(v => RegEnable(v, s1_fire))
  private val s2_vcSlotEntries_opt    = s1_vcSlotEntries_opt.map(v => RegEnable(v, s1_fire))
  private val s2_startPcVec_opt       = s1_startPcVec_opt.map(v => RegEnable(v, s1_fire))

  /* *** s2 ***
   * receive read response from alignBanks
   * assign SRAM predictions to result[0..7] and VC predictions to result[8..9]
   * drop VC slots that duplicate an SRAM hit at the same position, and invalidate that VC entry
   * send out prediction result and meta info
   */
  s2_fire := io.stageCtrl.s2_fire && io.enable

  private val s2_rawPredictions = VecInit(alignBanks.flatMap(_.io.read.resp.predictions))
  io.meta.entries := VecInit(alignBanks.map(_.io.read.resp.metas))

  // VC slot valid after duplicate check, piped to S3 for replacer touch
  private val s2_vcSlotValid_opt = Option.when(HasVC)(Wire(Vec(NumVCResultSlots, Bool())))
  private val s2_vcSlotDup_opt   = Option.when(HasVC)(Wire(Vec(NumVCResultSlots, Bool())))

  // Assign io.result: SRAM predictions + dedicated VC slots
  if (HasVC) {
    // SRAM part (0..NumWay*NumAlignBanks-1): pure SRAM, no VC merge
    for (k <- 0 until NumWay * NumAlignBanks) {
      io.result(k) := s2_rawPredictions(k)
    }
    // VC slots (NumWay*NumAlignBanks..NumBtbPredEntries-1)
    val vcSlotInfos   = s2_vcSlotInfos_opt.get
    val vcSlotEntries = s2_vcSlotEntries_opt.get
    val startPcVec    = s2_startPcVec_opt.get
    val vcSlotValid   = s2_vcSlotValid_opt.get
    val vcSlotDup     = s2_vcSlotDup_opt.get
    for (s <- 0 until NumVCResultSlots) {
      val idx          = NumWay * NumAlignBanks + s
      val vcEntry      = vcSlotEntries(s)
      val info         = vcSlotInfos(s)
      val slotPosition = Cat(info.posHigherBits, vcEntry.position)

      // SRAM already predicts this position, so the VC copy is stale: drop it and invalidate the entry
      vcSlotDup(s) := info.hit &&
        s2_rawPredictions.map(pred => pred.valid && pred.bits.cfiPosition === slotPosition).reduce(_ || _)
      vcSlotValid(s) := info.hit && !vcSlotDup(s)

      io.result(idx).valid            := vcSlotValid(s)
      io.result(idx).bits.cfiPosition := slotPosition
      io.result(idx).bits.target := getFullTarget(
        Mux1H(UIntToOH(info.sourceAlignBank, NumAlignBanks), startPcVec),
        vcEntry.targetLowerBits,
        Some(vcEntry.targetCarry)
      )
      io.result(idx).bits.attribute := vcEntry.attribute
      io.result(idx).bits.taken     := vcEntry.counter.isPositive

      alignBanks.zipWithIndex.foreach { case (b, j) =>
        b.io.s2_vcInvalidate.get(s).valid := s2_fire && vcSlotDup(s) && info.sourceAlignBank === j.U
        b.io.s2_vcInvalidate.get(s).bits  := info.vcIdx
      }
    }
  } else {
    io.result := s2_rawPredictions
  }

  // VC meta output
  io.meta.vcSlotMetas.foreach { vcSlotMetas =>
    val vcSlotInfos   = s2_vcSlotInfos_opt.get
    val vcSlotEntries = s2_vcSlotEntries_opt.get
    val vcSlotValid   = s2_vcSlotValid_opt.get
    for (s <- 0 until NumVCResultSlots) {
      val vcEntry = vcSlotEntries(s)
      val info    = vcSlotInfos(s)
      vcSlotMetas(s).rawHit    := vcSlotValid(s)
      vcSlotMetas(s).position  := Cat(info.posHigherBits, vcEntry.position)
      vcSlotMetas(s).attribute := vcEntry.attribute
      vcSlotMetas(s).counter   := vcEntry.counter
      vcSlotMetas(s).sramValid.foreach(_ := false.B)
      vcSlotMetas(s).sramTag.foreach(_ := 0.U)
      vcSlotMetas(s).targetLowerBits.foreach(_ := vcEntry.targetLowerBits)
      vcSlotMetas(s).targetCarry.foreach(_ := vcEntry.targetCarry)
    }
  }

  /* *** s3 ***
   * touch replacer using final takenMask (mbtb + tage + sc)
   */
  s3_fire := io.enable && io.stageCtrl.s3_fire
  // io.result is flattened, so is s3_takenMask from Bpu top, here we need to slice it back to alignBank structure
  alignBanks.zipWithIndex.foreach { case (b, i) =>
    b.io.s3_takenMask := io.s3_takenMask.slice(i * NumWay, (i + 1) * NumWay)
  }

  // VC S3 PLRU predTouch: route each taken VC slot to the alignBank that provided it
  if (HasVC) {
    val s3_vcSlotInfos = RegEnable(s2_vcSlotInfos_opt.get, s2_fire)
    val s3_vcSlotValid = RegEnable(s2_vcSlotValid_opt.get, s2_fire)
    for (s <- 0 until NumVCResultSlots) {
      val vcTaken = io.s3_takenMask(NumWay * NumAlignBanks + s)
      alignBanks.zipWithIndex.foreach { case (b, j) =>
        b.io.s3_vcPredTouch.get(s).valid :=
          s3_fire && s3_vcSlotValid(s) && vcTaken && s3_vcSlotInfos(s).sourceAlignBank === j.U
        b.io.s3_vcPredTouch.get(s).bits := s3_vcSlotInfos(s).vcIdx
      }
    }
  }

  /* *** t0 ***
   * receive training data and latch
   */
  private val t0_fire  = io.stageCtrl.t0_fire && io.enable
  private val t0_train = io.train

  /* *** t1 ***
   * calculate write data and write to alignBanks
   */
  private val t1_fire  = RegNext(t0_fire, init = false.B) && io.enable
  private val t1_train = RegEnable(t0_train, t0_fire)

  private val t1_startPc = t1_train.startPc
  private val t1_rotator = VecRotate(getAlignBankIndex(t1_startPc))
  private val t1_startPcVec = t1_rotator.rotate(
    VecInit.tabulate(NumAlignBanks)(i => getAlignedPc(t1_startPc + (i << FetchBlockAlignWidth).U))
  )
  private val t1_posHigherBitsVec = t1_rotator.rotate(VecInit.tabulate(NumAlignBanks)(_.U(AlignBankIdxLen.W)))
  private val t1_meta             = t1_train.meta.mbtb
  private val t1_mispredictInfo   = t1_train.mispredictBranch

  private val t1_writeAlignBankIdx  = getAlignBankIndexFromPosition(t1_mispredictInfo.bits.cfiPosition)
  private val t1_writeAlignBankMask = t1_rotator.rotate(VecInit(UIntToOH(t1_writeAlignBankIdx).asBools))

  alignBanks.zipWithIndex.foreach { case (b, i) =>
    b.io.write.req.valid              := t1_fire
    b.io.write.req.bits.needWrite     := t1_writeAlignBankMask(i)
    b.io.write.req.bits.startPc       := t1_startPcVec(i)
    b.io.write.req.bits.posHigherBits := t1_posHigherBitsVec(i)
    b.io.write.req.bits.branches      := t1_train.branches
    b.io.write.req.bits.meta          := t1_meta.entries(i)
    // see comments in MainBtbAlignBank.scala
    b.io.write.req.bits.mispredictInfo := t1_mispredictInfo
  }

  /* *** predecode-triggered SRAM + VC invalidation *** */
  private val pdInval_r0_fire = io.pdInvalidate.valid
  private val pdInval_r1_fire = RegNext(pdInval_r0_fire, false.B)
  private val pdInval_r1_req  = RegEnable(io.pdInvalidate.bits, pdInval_r0_fire)

  private val pdInval_r1_cfiPc           = pdInval_r1_req.cfiPc
  private val pdInval_r1_alignBankIdx    = getAlignBankIndex(pdInval_r1_cfiPc)
  private val pdInval_r1_setIdx          = getSetIndex(pdInval_r1_cfiPc)
  private val pdInval_r1_internalBankIdx = getInternalBankIndex(pdInval_r1_cfiPc)
  private val pdInval_r1_fullPosition = Cat(
    getAlignBankIndex(pdInval_r1_cfiPc),
    getAlignedInstOffset(pdInval_r1_cfiPc)
  )
  private val pdInval_r1_activeMeta = pdInval_r1_req.mbtbMeta.entries(pdInval_r1_alignBankIdx)
  private val pdInval_r1_wayMask = VecInit(
    pdInval_r1_activeMeta.map(m => m.rawHit && m.position === pdInval_r1_fullPosition)
  ).asUInt
  private val pdInval_r1_alignBankMask = UIntToOH(pdInval_r1_alignBankIdx, NumAlignBanks)

  // SRAM flush dispatch
  alignBanks.zipWithIndex.foreach { case (b, i) =>
    b.io.pdFlush.valid                := pdInval_r1_fire && pdInval_r1_wayMask.orR && pdInval_r1_alignBankMask(i)
    b.io.pdFlush.bits.setIdx          := pdInval_r1_setIdx
    b.io.pdFlush.bits.internalBankIdx := pdInval_r1_internalBankIdx
    b.io.pdFlush.bits.wayMask         := pdInval_r1_wayMask
  }

  // VC invalidation dispatch (always fire to the addressed alignBank, no-op if no match)
  alignBanks.zipWithIndex.foreach { case (b, i) =>
    b.io.pdVcInvalidate.foreach { pd =>
      pd.valid                := pdInval_r1_fire && pdInval_r1_alignBankMask(i)
      pd.bits.internalBankIdx := pdInval_r1_internalBankIdx
      pd.bits.vcTag           := makeVCTag(pdInval_r1_cfiPc)
      pd.bits.position        := getAlignedInstOffset(pdInval_r1_cfiPc)
    }
  }

  XSPerfAccumulate("pd_invalidate", pdInval_r1_fire && pdInval_r1_wayMask.orR)

  /* --------------------------------------------------------------------------------------------------------------
     MainBTB Trace
     -------------------------------------------------------------------------------------------------------------- */
  private val alignBankTraceVec = alignBanks.map(_.io.trace)
  private val finalTrace        = Mux1H(t1_writeAlignBankMask, alignBankTraceVec)
  private val finalTraceStartPc = Mux1H(t1_writeAlignBankMask, t1_startPcVec)
  private val mbtbTrace         = Wire(new MainBtbTrace)

  mbtbTrace.startPc      := finalTraceStartPc
  mbtbTrace.setIdx       := finalTrace.setIdx
  mbtbTrace.internalIdx  := finalTrace.bankIdx
  mbtbTrace.alignBankIdx := PriorityEncoder(t1_writeAlignBankMask)
  mbtbTrace.wayIdx       := finalTrace.wayIdx
  mbtbTrace.attribute    := finalTrace.entry.attribute
  mbtbTrace.cfiPosition  := finalTrace.entry.position

  private val mbtbTraceDBTable = ChiselDB.createTable("MBTBTrace", new MainBtbTrace(), EnableMainbtbTrace)
  mbtbTraceDBTable.log(
    data = mbtbTrace,
    en = t1_fire && finalTrace.needWrite,
    clock = clock,
    reset = reset
  )

  /* *** statistics *** */
  private val perf_s2HitMask             = VecInit(alignBanks.flatMap(_.io.read.resp.predictions.map(_.valid)))
  private val perf_t1HitMispredictBranch = t1_meta.allMetaEntries.map(_.hit(t1_mispredictInfo.bits)).reduce(_ || _)

  XSPerfAccumulate("total_train", t1_fire)
  XSPerfAccumulate("pred_hit", s2_fire && perf_s2HitMask.reduce(_ || _))
  XSPerfHistogram("pred_hit_count", PopCount(perf_s2HitMask), s2_fire, 0, NumWay * NumAlignBanks + 1)
  XSPerfAccumulate("train_has_mispredict", t1_fire && t1_mispredictInfo.valid)
  XSPerfAccumulate("train_hit_mispredict", t1_fire && t1_mispredictInfo.valid && perf_t1HitMispredictBranch)
  XSPerfAccumulate("pred_miss", s2_fire && perf_s2HitMask.reduce(!_ && !_))

  if (HasVC) {
    val perf_s2VcSlotValid = s2_vcSlotValid_opt.get
    val perf_s2VcSlotDup   = s2_vcSlotDup_opt.get
    XSPerfAccumulate("vc_s2_hit", s2_fire && perf_s2VcSlotValid.reduce(_ || _))
    XSPerfAccumulate("vc_s2_dup_flush", Mux(s2_fire, PopCount(perf_s2VcSlotDup), 0.U))
  }
}
