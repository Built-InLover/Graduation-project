package corewithbus

import chisel3._
import chisel3.util._
import common._

case class ICacheConfig(
  addrBits: Int = 32,
  dataBits: Int = 32,
  nSets: Int = 64,
  nWays: Int = 4,
  lineBytes: Int = 4,
  cacheableRegions: Seq[(BigInt, BigInt)] = Seq(
    (BigInt("30000000", 16), BigInt("F0000000", 16)),
    (BigInt("A0000000", 16), BigInt("F0000000", 16))
  )
) {
  require(nSets > 0 && (nSets & (nSets - 1)) == 0, "nSets must be a power of two")
  require(nWays > 0 && (nWays & (nWays - 1)) == 0, "nWays must be a power of two")
  require(lineBytes > 0 && (lineBytes & (lineBytes - 1)) == 0, "lineBytes must be a power of two")
  require(dataBits == 32, "the first icache version only supports a 32-bit data bus")
  require(lineBytes * 8 == dataBits, "the first icache version refills one bus beat per line")

  val offsetBits = log2Ceil(lineBytes)
  val indexBits  = log2Ceil(nSets)
  val tagBits    = addrBits - offsetBits - indexBits
  val wayBits    = math.max(1, log2Ceil(nWays))
}

class ICacheReq(cfg: ICacheConfig) extends Bundle {
  val addr = UInt(cfg.addrBits.W)
}

class ICacheResp(cfg: ICacheConfig) extends Bundle {
  val data      = UInt(cfg.dataBits.W)
  val exception = Bool()
}

class ICache(cfg: ICacheConfig = ICacheConfig()) extends Module {
  val io = IO(new Bundle {
    val cpu = new Bundle {
      val req  = Flipped(Decoupled(new ICacheReq(cfg)))
      val resp = Decoupled(new ICacheResp(cfg))
    }
    val bus = new AXI4Interface(AXI4Params(cfg.addrBits, cfg.dataBits, 4))
  })

  val sIdle :: sLookup :: sMissReq :: sMissResp :: sResp :: Nil = Enum(5)
  val state = RegInit(sIdle)

  val reqAddrReg       = Reg(UInt(cfg.addrBits.W))
  val reqIndexReg      = Reg(UInt(cfg.indexBits.W))
  val reqTagReg        = Reg(UInt(cfg.tagBits.W))
  val reqCacheableReg  = RegInit(false.B)
  val refillEnableReg  = RegInit(false.B)
  val victimWayReg     = Reg(UInt(cfg.wayBits.W))
  val respDataReg      = Reg(UInt(cfg.dataBits.W))
  val respExceptionReg = RegInit(false.B)

  val validArray = RegInit(VecInit(Seq.fill(cfg.nSets)(VecInit(Seq.fill(cfg.nWays)(false.B)))))
  val tagArray   = RegInit(VecInit(Seq.fill(cfg.nSets)(VecInit(Seq.fill(cfg.nWays)(0.U(cfg.tagBits.W))))))
  val dataArray  = RegInit(VecInit(Seq.fill(cfg.nSets)(VecInit(Seq.fill(cfg.nWays)(0.U(cfg.dataBits.W))))))
  val rrPtr      = RegInit(VecInit(Seq.fill(cfg.nSets)(0.U(cfg.wayBits.W))))

  private def lineAddr(addr: UInt): UInt = Cat(addr(cfg.addrBits - 1, cfg.offsetBits), 0.U(cfg.offsetBits.W))
  private def setIndex(addr: UInt): UInt = addr(cfg.offsetBits + cfg.indexBits - 1, cfg.offsetBits)
  private def setTag(addr: UInt): UInt = addr(cfg.addrBits - 1, cfg.offsetBits + cfg.indexBits)

  private def isCacheable(addr: UInt): Bool = {
    val hits = cfg.cacheableRegions.map { case (base, mask) =>
      val maskLit = mask.U(cfg.addrBits.W)
      val baseLit = (base & mask).U(cfg.addrBits.W)
      (addr & maskLit) === baseLit
    }
    hits.reduce(_ || _)
  }

  val lookupValids = validArray(reqIndexReg)
  val lookupTags   = tagArray(reqIndexReg)
  val lookupData   = dataArray(reqIndexReg)

  val hitVec = Wire(Vec(cfg.nWays, Bool()))
  val invalidVec = Wire(Vec(cfg.nWays, Bool()))
  for (way <- 0 until cfg.nWays) {
    hitVec(way) := lookupValids(way) && (lookupTags(way) === reqTagReg)
    invalidVec(way) := !lookupValids(way)
  }

  val hit = hitVec.asUInt.orR
  val hitWay = PriorityEncoder(hitVec)
  val hasInvalidWay = invalidVec.asUInt.orR
  val refillWay = Mux(hasInvalidWay, PriorityEncoder(invalidVec), rrPtr(reqIndexReg))

  io.cpu.req.ready := state === sIdle
  io.cpu.resp.valid := state === sResp
  io.cpu.resp.bits.data := respDataReg
  io.cpu.resp.bits.exception := respExceptionReg

  io.bus.aw.valid := false.B
  io.bus.aw.bits := DontCare
  io.bus.w.valid := false.B
  io.bus.w.bits := DontCare
  io.bus.b.ready := true.B

  io.bus.ar.valid := state === sMissReq
  io.bus.ar.bits.addr := lineAddr(reqAddrReg)
  io.bus.ar.bits.id := 0.U
  io.bus.ar.bits.len := 0.U
  io.bus.ar.bits.size := log2Ceil(cfg.dataBits / 8).U
  io.bus.ar.bits.burst := 1.U

  io.bus.r.ready := state === sMissResp

  when(io.cpu.req.fire) {
    reqAddrReg := io.cpu.req.bits.addr
    reqIndexReg := setIndex(io.cpu.req.bits.addr)
    reqTagReg := setTag(io.cpu.req.bits.addr)
    reqCacheableReg := isCacheable(io.cpu.req.bits.addr)
    state := sLookup
  }

  switch(state) {
    is(sLookup) {
      when(reqCacheableReg && hit) {
        respDataReg := lookupData(hitWay)
        respExceptionReg := false.B
        state := sResp
      }.otherwise {
        refillEnableReg := reqCacheableReg
        victimWayReg := refillWay
        state := sMissReq
      }
    }

    is(sMissReq) {
      when(io.bus.ar.fire) {
        state := sMissResp
      }
    }

    is(sMissResp) {
      when(io.bus.r.fire) {
        respDataReg := io.bus.r.bits.data
        respExceptionReg := io.bus.r.bits.resp =/= 0.U

        when(refillEnableReg && (io.bus.r.bits.resp === 0.U)) {
          for (set <- 0 until cfg.nSets) {
            when(reqIndexReg === set.U) {
              validArray(set)(victimWayReg) := true.B
              tagArray(set)(victimWayReg) := reqTagReg
              dataArray(set)(victimWayReg) := io.bus.r.bits.data
              rrPtr(set) := victimWayReg + 1.U
            }
          }
        }

        state := sResp
      }
    }

    is(sResp) {
      when(io.cpu.resp.fire) {
        state := sIdle
      }
    }
  }
}
