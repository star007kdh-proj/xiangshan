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
import xiangshan.frontend.bpu.replacer.PlruStateGen

class MainBtbVCReplacer(implicit p: Parameters) extends MainBtbModule {
  require(HasVC, "MainBtbVCReplacer instantiated without VC enabled")

  class MainBtbVCReplacerIO extends Bundle {
    // T1 actual-taken touches — one per VC result slot (chained first)
    val takenTouch: Vec[Valid[UInt]] = Vec(NumVCResultSlots, Flipped(Valid(UInt(log2Ceil(VCSize).W))))
    // T1 allocation/update touch — single (chained after takenTouch)
    val trainTouch: Valid[UInt] = Flipped(Valid(UInt(log2Ceil(VCSize).W)))
    // Valid bits from the VC register file, for invalid-first priority
    val validBits: UInt = Input(UInt(VCSize.W))
    // Victim index output
    val victim: UInt = Output(UInt(log2Ceil(VCSize).W))
  }

  val io: MainBtbVCReplacerIO = IO(new MainBtbVCReplacerIO)

  // Single PLRU state register (fully-associative, one set)
  private val plruState = RegInit(0.U((VCSize - 1).W))

  // Phase 1: actual-taken touches (up to NumVCResultSlots simultaneous)
  private val predStateGen = Module(new PlruStateGen(VCSize, AccessSize = NumVCResultSlots))
  predStateGen.io.state   := plruState
  predStateGen.io.touches := io.takenTouch
  private val anyPredTouch  = io.takenTouch.map(_.valid).reduce(_ || _)
  private val afterPredState = Mux(anyPredTouch, predStateGen.io.nextState, plruState)

  // Phase 2: allocation/update touch (chained after taken touch)
  private val trainStateGen = Module(new PlruStateGen(VCSize, AccessSize = 1))
  trainStateGen.io.state      := afterPredState
  trainStateGen.io.touches(0) := io.trainTouch
  private val afterTrainState = Mux(io.trainTouch.valid, trainStateGen.io.nextState, afterPredState)

  // Write back final state
  plruState := afterTrainState

  // Victim selection: invalid-first, then PLRU
  private val hasInvalid = !io.validBits.andR
  private val invalidIdx = PriorityEncoder(~io.validBits)
  // PLRU victim is computed from afterPredState (state after taken touch, before train touch)
  io.victim := Mux(hasInvalid, invalidIdx, trainStateGen.io.victim)
}
