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
import utility.ParallelPriorityEncoder
import utils.AddrField
import xiangshan.HasXSParameter
import xiangshan.frontend.PrunedAddr
import xiangshan.frontend.bpu.CrossPageHelper
import xiangshan.frontend.bpu.HalfAlignHelper
import xiangshan.frontend.bpu.TargetFixHelper

trait Helpers extends HasMainBtbParameters
    with HasXSParameter with TargetFixHelper with HalfAlignHelper with CrossPageHelper {

  val addrFields = AddrField(
    Seq(
      ("alignOffset", FetchBlockAlignWidth),
      ("alignBankIdx", AlignBankIdxLen),
      ("internalBankIdx", InternalBankIdxLen),
      ("setIdx", SetIdxLen),
      ("tag", TagWidth)
    ),
    maxWidth = Option(VAddrBits),
    extraFields = Seq(
      ("replacerSetIdx", FetchBlockSizeWidth, SetIdxLen),
      ("targetLower", instOffsetBits, TargetWidth),
      ("position", instOffsetBits, CfiAlignedPositionWidth),
      ("cfiPosition", instOffsetBits, CfiPositionWidth)
    )
  )

  def getSetIndex(pc: PrunedAddr): UInt =
    addrFields.extract("setIdx", pc)

  def getReplacerSetIndex(pc: PrunedAddr): UInt =
    addrFields.extract("replacerSetIdx", pc)

  def getAlignBankIndex(pc: PrunedAddr): UInt =
    addrFields.extract("alignBankIdx", pc)

  def getAlignBankIndexFromPosition(cfiPosition: UInt): UInt =
    addrFields.extractFrom("cfiPosition", "alignBankIdx", cfiPosition)

  def getTargetUpper(pc: PrunedAddr): UInt =
    pc(pc.length - 1, addrFields.getEnd("targetLower") + 1)

  def getTargetLowerBits(target: PrunedAddr): UInt =
    addrFields.extract("targetLower", target)

  def getInternalBankIndex(pc: PrunedAddr): UInt =
    addrFields.extract("internalBankIdx", pc)

  def getTag(pc: PrunedAddr): UInt =
    addrFields.extract("tag", pc)

  // VC tag from a per-AlignBank startPc, alignBank / internalBank are implied by the VC instance
  def makeVCTag(pc: PrunedAddr): UInt =
    Cat(getTag(pc), getSetIndex(pc))

  // Fully-associative VC lookup returning up to two hits: lower half first, upper half second
  def vcLookup(entries: Vec[VCEntry], vcTag: UInt, alignedInstOffset: UInt, crossPage: Bool): VCLookupResp = {
    val size     = entries.length
    val halfSize = size / 2
    val hitBits = VecInit(entries.map { e =>
      e.valid && e.vcTag === vcTag && e.position >= alignedInstOffset && !crossPage
    }).asUInt
    val hitLo = hitBits(halfSize - 1, 0)
    val hitHi = hitBits(size - 1, halfSize)
    val loHit = hitLo.orR
    val hiHit = hitHi.orR
    // balanced-tree encoders on each half, so the two hits are found independently
    val loInnerIdx = ParallelPriorityEncoder(hitLo)
    val hiInnerIdx = ParallelPriorityEncoder(hitHi)
    val loEntry    = VecInit(entries.take(halfSize))(loInnerIdx)
    val hiEntry    = VecInit(entries.drop(halfSize))(hiInnerIdx)
    val loIdx      = Cat(0.U(1.W), loInnerIdx)
    val hiIdx      = Cat(1.U(1.W), hiInnerIdx)

    // hit2 implies hit1: if only the upper half hits, promote it into the first slot
    val resp = Wire(new VCLookupResp)
    resp.hit1   := loHit || hiHit
    resp.vcIdx1 := Mux(loHit, loIdx, hiIdx)
    resp.entry1 := Mux(loHit, loEntry, hiEntry)
    resp.hit2   := loHit && hiHit
    resp.vcIdx2 := hiIdx
    resp.entry2 := hiEntry
    resp
  }

  // detect multi-hit, return a mask indicating which way has multi-hit
  def detectMultiHit(hitMask: IndexedSeq[Bool], position: IndexedSeq[UInt]): UInt = {
    require(hitMask.length == position.length)
    require(hitMask.length >= 2)
    val multiHitMask = VecInit(Seq.fill(NumWay)(false.B))
    for {
      i <- 0 until NumWay
      j <- i + 1 until NumWay
    } {
      val bothHit      = hitMask(i) && hitMask(j)
      val samePosition = position(i) === position(j)
      when(bothHit && samePosition) {
        multiHitMask(j) := true.B
      }
    }
    PriorityEncoderOH(multiHitMask.asUInt)
  }
}
