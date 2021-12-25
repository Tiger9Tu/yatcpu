// Copyright 2021 Howard Lau
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package riscv

import chisel3._
import chisel3.util._

class BusSwitch extends Module {
  val io = IO(new Bundle {
    val address = Input(UInt(Parameters.AddrWidth))
    val slaves = Vec(Parameters.SlaveDeviceCount, new AXI4LiteChannels(Parameters.AddrBits, Parameters.DataBits))
    val master = Flipped(new AXI4LiteChannels(Parameters.AddrBits, Parameters.DataBits))
  })
  val dummy = Module(new DummyMaster)
  val index = io.address(Parameters.AddrBits - 1, Parameters.AddrBits - 1 - Parameters.SlaveDeviceCountBits)
  io.master <> io.slaves(0)
  dummy.io.channels <> io.slaves(0)
  for (i <- 0 until Parameters.SlaveDeviceCount) {
    when(index === i.U) {
      io.slaves(i) <> io.master
    }.otherwise{
      io.slaves(i) <> dummy.io.channels
    }
  }
}
