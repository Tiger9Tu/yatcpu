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

package board.basys3

import board.common.{FontROM, InstructionROM, VGADisplay, VGASync}
import chisel3._
import chisel3.experimental.ChiselEnum
import chisel3.util._
import riscv._
import _root_.peripheral.{DummySlave, Memory, ROMLoader, Timer, Uart}
import _root_.bus.{BusArbiter, BusSwitch}
import riscv.core.{CPU, ProgramCounter}

object BootStates extends ChiselEnum {
  val Init, Loading, Finished = Value
}

class Top extends Module {
  val binaryFilename = "tetris.asmbin"
  val io = IO(new Bundle {
    val switch = Input(UInt(16.W))

    val segs = Output(UInt(8.W))
    val digit_mask = Output(UInt(4.W))

    val hsync = Output(Bool())
    val vsync = Output(Bool())
    val rgb = Output(UInt(12.W))
    val led = Output(UInt(16.W))

    val tx = Output(Bool())
    val rx = Input(Bool())
  })
  val boot_state = RegInit(BootStates.Init)

  val uart = Module(new Uart(100000000, 115200))
  io.tx := uart.io.txd
  uart.io.rxd := io.rx

  val cpu = Module(new CPU)
  val mem = Module(new Memory(Parameters.MemorySizeInWords))
  val timer = Module(new Timer)
  val dummy = Module(new DummySlave)
  val bus_arbiter = Module(new BusArbiter)
  val bus_switch = Module(new BusSwitch)

  val instruction_rom = Module(new InstructionROM(binaryFilename))
  val rom_loader = Module(new ROMLoader(instruction_rom.capacity))
  bus_arbiter.io.bus_request(0) := true.B

  bus_switch.io.master <> cpu.io.axi4_channels
  bus_switch.io.address := cpu.io.bus_address
  for (i <- 0 until Parameters.SlaveDeviceCount) {
    bus_switch.io.slaves(i) <> dummy.io.channels
  }
  rom_loader.io.load_address := ProgramCounter.EntryAddress
  rom_loader.io.load_start := false.B
  rom_loader.io.rom_data := instruction_rom.io.data
  instruction_rom.io.address := rom_loader.io.rom_address
  cpu.io.stall_flag_bus := true.B
  cpu.io.instruction_valid := false.B
  bus_switch.io.slaves(0) <> mem.io.channels
  rom_loader.io.channels <> dummy.io.channels
  switch(boot_state) {
    is(BootStates.Init) {
      rom_loader.io.load_start := true.B
      boot_state := BootStates.Loading
      rom_loader.io.channels <> mem.io.channels
    }
    is(BootStates.Loading) {
      rom_loader.io.load_start := false.B
      rom_loader.io.channels <> mem.io.channels
      when(rom_loader.io.load_finished) {
        boot_state := BootStates.Finished
      }
    }
    is(BootStates.Finished) {
      cpu.io.stall_flag_bus := false.B
      cpu.io.instruction_valid := true.B
    }
  }
  bus_switch.io.slaves(2) <> uart.io.channels
  bus_switch.io.slaves(4) <> timer.io.channels

  cpu.io.interrupt_flag := Cat(uart.io.signal_interrupt, timer.io.signal_interrupt)

  cpu.io.debug_read_address := 0.U
  mem.io.debug_read_address := 0.U

  val vga_display = Module(new VGADisplay)
  val vga_sync = Module(new VGASync)
  val font_rom = Module(new FontROM)
  vga_display.io.screen_x := vga_sync.io.x
  vga_display.io.screen_y := vga_sync.io.y
  io.hsync := vga_sync.io.hsync
  io.vsync := vga_sync.io.vsync

  mem.io.char_read_address := vga_display.io.char_mem_address
  vga_display.io.char_mem_data := mem.io.char_read_data

  font_rom.io.glyph_index := vga_display.io.glyph_rom_index
  font_rom.io.glyph_y := vga_display.io.glyph_y
  io.rgb := Mux(vga_sync.io.video_on && font_rom.io.glyph_pixel_byte(vga_display.io.glyph_x).asBool(), 0xFFFF.U, 0.U)

  mem.io.debug_read_address := io.switch(15, 1).asUInt() << 2
  io.led := Mux(
    io.switch(0),
    mem.io.debug_read_data(31, 16).asUInt(),
    mem.io.debug_read_data(15, 0).asUInt(),
  )

  val onboard_display = Module(new OnboardDigitDisplay)
  io.digit_mask := onboard_display.io.digit_mask

  val sysu_logo = Module(new SYSULogo)
  sysu_logo.io.digit_mask := io.digit_mask

  val seg_mux = Module(new SegmentMux)
  seg_mux.io.digit_mask := io.digit_mask
  seg_mux.io.numbers := io.led

  io.segs := MuxLookup(
    io.switch,
    seg_mux.io.segs,
    Array(
      0.U -> sysu_logo.io.segs
    )
  )
}
