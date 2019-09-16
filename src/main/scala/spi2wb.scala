package spi2wb

import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.Driver

// minimal signals definition for a wishbone bus 
// (no SEL, no TAG, no pipeline, ...)
class WbMaster (private val dwidth: Int,
                private val awidth: Int) extends Bundle {
    val adr_o = Output(UInt(awidth.W))
    val dat_i = Input(UInt(dwidth.W))
    val dat_o = Output(UInt(dwidth.W))
    val we_o = Output(Bool())
    val stb_o = Output(Bool())
    val ack_i = Input(Bool())
    val cyc_o = Output(Bool())
}

class SpiSlave extends Bundle {
    val mosi = Input(Bool())
    val miso = Output(Bool())
    val sclk = Input(Bool())
    val csn = Input(Bool())
}

class Spi2Wb (dwidth: Int, awidth: Int) extends Module {
  val io = IO(new Bundle{
    // Wishbone master output
    val wbm = new WbMaster(dwidth, awidth)
    // SPI signals
    val spi = new SpiSlave()
  })

  assert(dwidth == 8, "Only 8bits data actually supported")
  assert(awidth == 7, "Only 7bits address actually supported")

  // Wishbone init
  val wbWeReg  = RegInit(false.B)
  val wbStbReg = RegInit(false.B)
  val wbCycReg = RegInit(false.B)

  // CPOL  | leading edge | trailing edge
  // ------|--------------|--------------
  // false | rising       | falling
  // true  | falling      | rising
  val CPOL = false
  assert(CPOL==false, "Only CPOL==false supported")

  // CPHA  | data change    | data read
  // ------|----------------|--------------
  // false | trailling edge | leading edge
  // true  | leading edge   | trailing edge
  val CPHA = true
  assert(CPHA==true, "Only CPHA==true supported")

  def risingedge(x: Bool) = x && !RegNext(x)
  def fallingedge(x: Bool) = !x && RegNext(x)

  val misoReg = RegInit(true.B)
  val sclkReg = RegNext(io.spi.sclk)
  val csnReg =  RegNext(io.spi.csn)

  val valueReg = RegInit("hca".U(dwidth.W))
  val dataReg  = RegInit("h00".U(dwidth.W))
  val addrReg  = RegInit("h00".U(awidth.W))

  val wrReg  = RegInit(false.B)
  val wbFlag = RegInit(false.B)

  val count = RegInit("hff".U(dwidth.W))
  //   000    001     010     011        100     101            110
  val sinit::swrreg::saddr::sdataread::swbread::sdatawrite::swbwrite::Nil=Enum(7)
  val stateReg = RegInit(sinit)

  switch(stateReg) {
    is(sinit){
      wrReg := false.B
      count := 0.U
      addrReg := 0.U
      dataReg := 0.U
      misoReg := false.B
      wbWeReg  := false.B
      wbStbReg := false.B
      wbCycReg := false.B
      when(fallingedge(csnReg)){
        stateReg := swrreg
      }
    }
    is(swrreg){
      when(fallingedge(sclkReg)){
        wrReg := io.spi.mosi
        count := 1.U
        stateReg := saddr
      }
    }
    is(saddr){
      when(fallingedge(sclkReg)){
        addrReg := addrReg + (io.spi.mosi << (dwidth.U - count))
        count := count + 1.U
        when(count >= (dwidth.U - 1.U)) {
          when(wrReg){
            stateReg := sdatawrite
          }
          when(!wrReg){
            wbWeReg  := false.B
            wbStbReg := true.B
            wbCycReg := true.B
            stateReg := swbread
          }
        }
      }
    }
    is(swbread){
      valueReg := io.wbm.dat_i
      wbStbReg := false.B
      wbCycReg := false.B
      stateReg := sdataread
    }
    is(sdataread){
      when(risingedge(sclkReg)){
        misoReg := dataReg(2.U*dwidth.U - count)
        count := count + 1.U
      }
    }
    is(sdatawrite){
      when(fallingedge(sclkReg)){
        dataReg := dataReg(dwidth-2,0) ## io.spi.mosi
        count := count + 1.U
      }
      when(count >= 2.U*dwidth.U){
        stateReg := swbwrite
      }
    }
    is(swbwrite){
        wbWeReg  := true.B
        wbStbReg := true.B
        wbCycReg := true.B
        stateReg := sinit
    }
  }
  // reset state machine to sinit when csn rise
  // even if count is not right
  when(risingedge(csnReg)){
        stateReg := sinit
  }

  // spi signals
  io.spi.miso := misoReg
  // wishbone signals
  io.wbm.adr_o := addrReg
  io.wbm.dat_o := dataReg
  io.wbm.we_o  := wbWeReg
  io.wbm.stb_o := wbStbReg
  io.wbm.cyc_o := wbCycReg

}

// Blinking module to validate hardware
class BlinkLed extends Module {
  val io = IO(new Bundle{
    val blink = Output(Bool())
  })

  val blinkReg = RegNext(io.blink, false.B)
  io.blink := blinkReg
  val regSize = 24
  val max = "h989680".U

  val countReg = RegInit(0.U(regSize.W))

  countReg := countReg + 1.U
  when(countReg === max) {
    countReg := 0.U
    blinkReg := !blinkReg
  }

}


class MyMem (private val dwidth: Int, private val awidth: Int) extends Module {
  val io = IO(new Bundle{
    val wbm = Flipped(new WbMaster(dwidth, awidth))
  })
  // memory
  val wmem = SyncReadMem(1 << awidth, UInt(dwidth.W)) 

  val ackReg = RegInit(false.B)
  val datReg = RegInit(0.U(dwidth.W))

  ackReg := false.B
  datReg := 0.U(dwidth.W)
  when(io.wbm.stb_o && io.wbm.cyc_o) {
    when(io.wbm.we_o){
      wmem.write(io.wbm.adr_o, io.wbm.dat_o)
      datReg := DontCare
    }.otherwise{
      datReg := wmem.read(io.wbm.adr_o, io.wbm.stb_o &
                          io.wbm.cyc_o & !io.wbm.we_o)
    }
    ackReg := true.B
  }
  io.wbm.dat_i := datReg
  io.wbm.ack_i := ackReg
}


// Testing Spi2Wb with a memory connnexion
class TopSpi2Wb extends RawModule {
  // Clock & Reset
  val clock = IO(Input(Clock()))
  val rstn  = IO(Input(Bool()))

  // Blink
  val blink = IO(Output(Bool()))

  // SPI
  val mosi = IO(Input(Bool()))
  val miso = IO(Output(Bool()))
  val sclk = IO(Input(Bool()))
  val csn  = IO(Input(Bool()))
  val deb_wbm = IO(new WbMaster(8, 7))
  val deb_adr_o = IO(Output(Bool()))  
  val deb_dat_o = IO(Output(Bool())) 
  val deb_we_o  = IO(Output(Bool())) 
  val deb_stb_o = IO(Output(Bool())) 
  val deb_cyc_o = IO(Output(Bool())) 

  val dwidth = 8
  val awidth = 7

  withClockAndReset(clock, !rstn) {
    // Blink connections
    val blinkModule = Module(new BlinkLed)
    blink := blinkModule.io.blink

    // SPI to wb connections
    val slavespi = Module(new Spi2Wb(dwidth, awidth))
    miso := slavespi.io.spi.miso
    // spi
    slavespi.io.spi.mosi := ShiftRegister(mosi, 2) // ShiftRegister
    slavespi.io.spi.sclk := ShiftRegister(sclk, 2) // used for clock
    slavespi.io.spi.csn  := ShiftRegister(csn, 2)  // synchronisation

    // wb memory connexion
    val mymem = Module(new MyMem(dwidth, awidth))
    mymem.io.wbm <> slavespi.io.wbm

    // Manage achnowledge
    // TODO delete following
    deb_wbm <> slavespi.io.wbm
    deb_adr_o := slavespi.io.wbm.adr_o
    deb_dat_o := slavespi.io.wbm.dat_o
    deb_we_o  := slavespi.io.wbm.we_o 
    deb_stb_o := slavespi.io.wbm.stb_o
    deb_cyc_o := slavespi.io.wbm.cyc_o
  }
}

object Spi2Wb extends App {
  println("****************************")
  println("* Generate verilog sources *")
  println("****************************")
  println("Virgin module")
  chisel3.Driver.execute(Array[String](), () => new Spi2Wb(8, 7))
  println("Real world module with reset inverted")
  chisel3.Driver.execute(Array[String](), () => new TopSpi2Wb())
}
