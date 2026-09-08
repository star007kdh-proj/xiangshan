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
import xiangshan.XSCoreParamsKey
import xiangshan.frontend.bpu.BranchAttribute
import xiangshan.frontend.bpu.Prediction
import xiangshan.frontend.bpu.SaturateCounter
import xiangshan.frontend.bpu.SaturateCounterFactory
import xiangshan.frontend.bpu.TargetCarry

object UsefulCounter extends SaturateCounterFactory {
  def width(implicit p: Parameters): Int =
    p(XSCoreParamsKey).frontendParameters.bpuParameters.ubtbParameters.UsefulCntWidth
}

/** uBTB entry. With `EnableTwoTaken`, `slot2` holds the chained second branch
 *  of a (B, C) pair; `isPair` qualifies whether the pair is currently alive.
 *  When EnableTwoTaken is false the pair fields are omitted (Option = None)
 *  and slot2 is held inert as in the baseline.
 */
class MicroBtbEntry(implicit p: Parameters) extends MicroBtbBundle {
  class SlotBase extends Bundle {
    // branch position: at fetchBlockVAddr + position
    val position: UInt = UInt(CfiPositionWidth.W)
    // branch attribute
    val attribute: BranchAttribute = new BranchAttribute
    // partial target: full target = Cat(fetchBlockVAddr(VAddrBits-1, TargetWidth), target)
    val target: UInt = UInt(TargetWidth.W)

    // used for target fix, see comment in Parameters.scala
    val targetCarry: Option[TargetCarry] = if (EnableTargetFix) Option(new TargetCarry) else None
  }

  class Slot1 extends SlotBase {
    // whether branch in slot 1 has a static target
    val isStaticTarget: Bool = Bool()
  }

  class Slot2 extends SlotBase {
    // whether branch in slot 2 is valid
    val valid: Bool = Bool()
    // whether branch in slot 2 is predicted as taken
    val taken: Bool = Bool()
    // pair confidence (2-bit saturating). Bumped on chain re-confirm, reset on re-alloc.
    val confidence: Option[UInt] = if (EnableTwoTaken) Option(UInt(PairConfWidth.W)) else None
    // mBTB always-taken bit of the slot 2 branch, copied from fastTrain at alloc/confirm (gem5 pairBranch.alwaysTaken)
    val alwaysTaken: Option[Bool] = if (EnableTwoTaken) Option(Bool()) else None
  }

  // we consider an entry is valid if it has usefulCnt > 0
  def valid: Bool = !usefulCnt.isSaturateNegative
  // partial vTag = fetchBlockVAddr(TagWidth, 1)
  val tag: UInt = UInt(TagWidth.W)
  // saturate counter indicating how useful is this entry
  val usefulCnt: SaturateCounter = UsefulCounter()

  val slot1: Slot1 = new Slot1
  val slot2: Slot2 = new Slot2

  /** True iff this entry currently holds a live (B, C) pair.
   *  Only present (and meaningful) when `EnableTwoTaken`.
   */
  val isPair: Option[Bool] = if (EnableTwoTaken) Option(Bool()) else None
}

class MicroBtbMeta(implicit p: Parameters) extends MicroBtbBundle {
  // seems no meta is needed now, reserved for future use
}

class ReplacerPerfInfo(implicit p: Parameters) extends MicroBtbBundle {
  val replaceNotUseful: Bool = Bool() // if not, replacePlru
}

/** uBTB pair lookup output. Drives BPU top's pair-fire decision in s1.
 *  Only instantiated when `EnableTwoTaken`. When the pair is not alive
 *  (entry miss, or hit without pair) `isPair` is false and BPU falls back
 *  to the single-prediction path on `MicroBtb.io.prediction`.
 */
class MicroBtbPairOut(implicit p: Parameters) extends MicroBtbBundle {
  /** Always-taken first branch (B). Equal to `MicroBtb.io.prediction.bits`
   *  when `isPair` is asserted.
   */
  val first: Prediction = new Prediction

  /** Always-taken second branch (C). `startPc` of the predicted second
   *  fetch-block equals `first.target`.
   */
  val second: Prediction = new Prediction

  /** Whether the lookup yields a usable pair (entry hit AND slot2 valid). */
  val isPair: Bool = Bool()

  /** Pair confidence (saturating); BPU emit gate uses an attribute-dependent threshold. */
  val confidence: UInt = UInt(PairConfWidth.W)

  /** Whether the second branch is an mBTB always-taken conditional (gem5 G6 gate input). */
  val secondAlwaysTaken: Bool = Bool()
}
