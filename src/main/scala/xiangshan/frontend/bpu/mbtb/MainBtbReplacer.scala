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
import xiangshan.frontend.bpu.replacer.ReplacerState
import xiangshan.frontend.bpu.replacer.ReplacerStateGen

// Training-touch replacer: the state is touched only at T1 (allocation or actual-taken hit), never at prediction
class MainBtbReplacer(implicit p: Parameters) extends MainBtbModule {
  class MainBtbReplacerIO extends Bundle {
    class Touch extends Bundle {
      val setIdx:  UInt = UInt(SetIdxLen.W)
      val wayMask: UInt = UInt(NumWay.W)
    }

    class Victim extends Bundle {
      val wayMask: UInt = UInt(NumWay.W)
    }

    val victim:     Victim       = Output(new Victim)
    val trainTouch: Valid[Touch] = Flipped(Valid(new Touch))
  }

  val io: MainBtbReplacerIO = IO(new MainBtbReplacerIO)

  private val trainStateGen = Module(ReplacerStateGen(Replacer, NumWay, accessSize = 1))
  private val stateBank     = Module(new ReplacerState(NumSets, trainStateGen.StateWidth))

  // shared state bank, prediction-side ports unused here
  stateBank.io.predictReadSetIdx  := 0.U
  stateBank.io.predictWriteValid  := false.B
  stateBank.io.predictWriteSetIdx := 0.U
  stateBank.io.predictWriteState  := 0.U

  /* *** train *** */
  // read current state
  stateBank.io.trainReadSetIdx := io.trainTouch.bits.setIdx
  private val trainState = stateBank.io.trainReadState

  // compose touch way vec
  private val trainTouchWay = Wire(Valid(UInt(log2Up(NumWay).W)))
  trainTouchWay.valid := io.trainTouch.valid
  trainTouchWay.bits  := OHToUInt(io.trainTouch.bits.wayMask) // MainBtbAlignBank ensures this is one-hot
  assert(
    !io.trainTouch.valid || PopCount(io.trainTouch.bits.wayMask) <= 1.U,
    "train touch wayMask should be at-most-one-hot"
  )

  // generate next state
  trainStateGen.io.state   := trainState
  trainStateGen.io.touches := VecInit(Seq(trainTouchWay))
  private val trainNextState = Mux(io.trainTouch.valid, trainStateGen.io.nextState, trainState)

  // write back next state
  stateBank.io.trainWriteValid  := io.trainTouch.valid
  stateBank.io.trainWriteSetIdx := io.trainTouch.bits.setIdx
  stateBank.io.trainWriteState  := trainNextState

  /* *** victim *** */
  io.victim.wayMask := UIntToOH(trainStateGen.io.victim)
}
