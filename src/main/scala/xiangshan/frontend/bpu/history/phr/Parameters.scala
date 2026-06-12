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

package xiangshan.frontend.bpu.history.phr

import xiangshan.frontend.bpu.HasBpuParameters

case class PhrParameters(
    Shamt:         Int = 2, // shift amount for Phr (single-update path)
    PathHashWidth: Int = 15,
    // ensure history length is a multiple of this value
    // default is 4, when history value is displayed in hexadecimal, it has better readability
    HistoryAlign: Int = 4
) {}

trait HasPhrParameters extends HasBpuParameters {
  def phrParameters: PhrParameters = bpuParameters.phrParameters

  def Shamt:             Int = phrParameters.Shamt
  def PathHashWidth:     Int = phrParameters.PathHashWidth
  def PathHashHighWidth: Int = PathHashWidth - Shamt
  def MaxHistLens:       Int = bpuParameters.tageParameters.TableInfos.map(_.HistoryLength).max

  // PHR effective shift per update cycle. With EnableTwoTaken, a pair-fire cycle
  // shifts 2 * Shamt bits; single dispatches still shift Shamt. The folded-history
  // update circuit must be sized for the worst case.
  def MaxPhrShamt: Int = if (EnableTwoTaken) 2 * Shamt else Shamt

  // inherited from HasBpuParameters
  // def PhrHistoryLength: Int = PhrHistoryLength
}
