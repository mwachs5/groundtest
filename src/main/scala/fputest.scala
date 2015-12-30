package groundtest

import Chisel._
import rocket._
import rocket.FPConstants._
import cde.Parameters

abstract class FPUTest(implicit p: Parameters) extends Module {
  val io = new Bundle {
    val finished = Bool(OUTPUT)
    val fpu = new FPSideIO
  }

  def requests: Vec[FPRequest]
  def expected: Vec[UInt]
  def test_name: String

  def resp_match(resp: UInt, exp: UInt): Bool

  def stou(f: Float): UInt =
    SInt(java.lang.Float.floatToIntBits(f), 32).toUInt

  def dtou(d: Double): UInt =
    SInt(java.lang.Double.doubleToLongBits(d), 64).toUInt

  val s_start :: s_test :: s_wait :: s_done :: Nil = Enum(Bits(), 4)
  val state = Reg(init = s_start)

  val maxXacts = 4
  val xactBits = log2Up(maxXacts)
  val req_busy = Reg(UInt(width = maxXacts))
  val exp_buffer = Reg(Vec(maxXacts, UInt(width = 64)))
  val resp_id = io.fpu.resp.bits.id(xactBits - 1, 0)

  def setBusy(fire: Bool, xact: UInt): UInt =
    Mux(fire, UIntToOH(xact), UInt(0))

  def clearBusy(fire: Bool, xact: UInt): UInt =
    ~Mux(fire, UIntToOH(xact), UInt(0))

  def elaborate() {
    val (req_count, req_done) = Counter(io.fpu.req.fire(), requests.size)

    val xact = req_count(xactBits - 1, 0)
    val req = requests(req_count)

    io.fpu.req.valid := !req_busy(xact) && (state === s_test)
    io.fpu.req.bits := req
    io.fpu.req.bits.id := xact

    req_busy := (req_busy &
                  clearBusy(io.fpu.resp.fire(), resp_id)) |
                  setBusy(io.fpu.req.fire(), xact)

    when (state === s_start) {
      state := s_test
      req_busy := UInt(0)
    }

    when (io.fpu.req.fire()) {
      exp_buffer(xact) := expected(req_count)
    }

    when (req_done) { state := s_wait }
    when (state === s_wait && !req_busy.toBits.orR) { state := s_done }

    io.finished := (state === s_done)

    assert(!io.fpu.resp.valid ||
           resp_match(io.fpu.resp.bits.data, exp_buffer(resp_id)),
           s"FPU Test $test_name: result does not match expected")
    assert(!io.fpu.resp.valid || io.fpu.resp.bits.exc === UInt(0),
           s"FPU Test $test_name exception")
  }
}

class SinglePrecFPUTest(implicit p: Parameters) extends FPUTest()(p) {
  case class SPTest(op: UInt, res: UInt,
    in1: Float, in2: Float = 0.0f, in3: Float = 0.0f,
    ftyp: UInt = FTYP_S, rm: UInt = RM_DEFAULT)

  val sp_tests = Seq(
    // 0.1 == 0.1
    SPTest(OP_FCMP, UInt(1), 0.1f, 0.1f, rm = RM_EQ),
    // 0.5 < 0.5
    SPTest(OP_FCMP, UInt(0), 0.5f, 0.5f, rm = RM_LT),
    // 0.2 < 0.5
    SPTest(OP_FCMP, UInt(1), 0.2f, 0.5f, rm = RM_LT),
    // 0.7 <= 2.4
    SPTest(OP_FCMP, UInt(1), 0.7f, 2.4f, rm = RM_LE),
    // min(0.3, 0.5)
    SPTest(OP_FMINMAX, stou(0.3f), 0.3f, 0.5f, rm = RM_MIN),
    // max(0.3, 0.5)
    SPTest(OP_FMINMAX, stou(0.5f), 0.3f, 0.5f, rm = RM_MAX),
    // 1.0 + 1.0
    SPTest(OP_FADD, stou(2.0f), 1.0f, 1.0f),
    // 1.0 - 1.0
    SPTest(OP_FSUB, stou(0.0f), 1.0f, 1.0f),
    // 2.0 * 1.0
    SPTest(OP_FMUL, stou(2.0f), 2.0f, 1.0f),
    // 2.0 * 1.0 + 0.5
    SPTest(OP_FMADD, stou(2.5f), 2.0f, 1.0f, 0.5f),
    // 4.0 / 2.0
    SPTest(OP_FDIV, stou(2.0f), 4.0f, 2.0f),
    // 4.0 / 8.0
    SPTest(OP_FDIV, stou(0.5f), 4.0f, 8.0f),
    // sqrt(4.0)
    SPTest(OP_FSQRT, stou(2.0f), 4.0f),
    // number classification
    SPTest(OP_FCLASS, Bits("b0000010000"), 0.0f, rm = RM_CLASS),
    SPTest(OP_FCLASS, Bits("b0000000010"), -1.0f, rm = RM_CLASS),
    SPTest(OP_FCLASS, Bits("b0001000000"), 1.0f, rm = RM_CLASS),
    // sign injection
    SPTest(OP_FSGNJ, stou(-2.0f), 2.0f, -1.0f, rm = RM_SGNJ),
    SPTest(OP_FSGNJ, stou(2.0f), -2.0f, -1.0f, rm = RM_SGNJN),
    SPTest(OP_FSGNJ, stou(2.0f), -2.0f, -1.0f, rm = RM_SGNJX))

  val requests = Vec(sp_tests.map { test =>
    val req = Wire(new FPRequest)
    req.op := test.op
    req.ftyp := test.ftyp
    req.ityp := UInt(0)
    req.in1 := stou(test.in1)
    req.in2 := stou(test.in2)
    req.in3 := stou(test.in3)
    req.rm := test.rm
    req
  })

  val expected = Vec(sp_tests.map(_.res))

  def resp_match(resp: UInt, exp: UInt): Bool =
    resp(31, 0) === exp(31, 0)

  def test_name = "SP"

  elaborate()
}

class DoublePrecFPUTest(implicit p: Parameters) extends FPUTest()(p) {
  case class DPTest(op: UInt, res: UInt,
    in1: Double, in2: Double = 0.0, in3: Double = 0.0,
    ftyp: UInt = FTYP_D, rm: UInt = RM_DEFAULT)

  val dp_tests = Seq(
    // 0.1 == 0.1
    DPTest(OP_FCMP, UInt(1), 0.1, 0.1, rm = RM_EQ),
    // 0.5 < 0.5
    DPTest(OP_FCMP, UInt(0), 0.5, 0.5, rm = RM_LT),
    // 0.2 < 0.5
    DPTest(OP_FCMP, UInt(1), 0.2, 0.5, rm = RM_LT),
    // 0.7 <= 2.4
    DPTest(OP_FCMP, UInt(1), 0.7, 2.4, rm = RM_LE),
    // min(0.3, 0.5)
    DPTest(OP_FMINMAX, dtou(0.3), 0.3, 0.5, rm = RM_MIN),
    // max(0.3, 0.5)
    DPTest(OP_FMINMAX, dtou(0.5), 0.3, 0.5, rm = RM_MAX),
    // 1.0 + 1.0
    DPTest(OP_FADD, dtou(2.0), 1.0, 1.0),
    // 1.0 - 1.0
    DPTest(OP_FSUB, dtou(0.0), 1.0, 1.0),
    // 2.0 * 1.0
    DPTest(OP_FMUL, dtou(2.0), 2.0, 1.0),
    // 2.0 * 1.0 + 0.5
    DPTest(OP_FMADD, dtou(2.5), 2.0, 1.0, 0.5),
    // 4.0 / 2.0
    DPTest(OP_FDIV, dtou(2.0), 4.0, 2.0),
    // 4.0 / 8.0
    DPTest(OP_FDIV, dtou(0.5), 4.0, 8.0),
    // sqrt(4.0)
    DPTest(OP_FSQRT, dtou(2.0), 4.0),
    // number classification
    DPTest(OP_FCLASS, Bits("b0000010000"), 0.0, rm = RM_CLASS),
    DPTest(OP_FCLASS, Bits("b0000000010"), -1.0, rm = RM_CLASS),
    DPTest(OP_FCLASS, Bits("b0001000000"), 1.0, rm = RM_CLASS),
    // sign injection
    DPTest(OP_FSGNJ, dtou(-2.0), 2.0, -1.0, rm = RM_SGNJ),
    DPTest(OP_FSGNJ, dtou(2.0), -2.0, -1.0, rm = RM_SGNJN),
    DPTest(OP_FSGNJ, dtou(2.0), -2.0, -1.0, rm = RM_SGNJX))

  val requests = Vec(dp_tests.map { test =>
    val req = Wire(new FPRequest)
    req.op := test.op
    req.ftyp := test.ftyp
    req.ityp := UInt(0)
    req.in1 := dtou(test.in1)
    req.in2 := dtou(test.in2)
    req.in3 := dtou(test.in3)
    req.rm := test.rm
    req
  })

  val expected = Vec(dp_tests.map(_.res))

  def resp_match(resp: UInt, exp: UInt): Bool =
    resp(63, 0) === exp(63, 0)

  def test_name = "DP"

  elaborate()
}

class ConvFPUTest(implicit p: Parameters) extends FPUTest()(p) {
  case class ConvTest(op: UInt, res: UInt, in: UInt, ftyp: UInt, ityp: UInt)

  val conv_tests = Seq(
    ConvTest(OP_FCVT_FI, stou(3.0f), UInt(3), FTYP_S, ITYP_W),
    ConvTest(OP_FCVT_FI, dtou(3.0), UInt(3), FTYP_D, ITYP_L),
    ConvTest(OP_FCVT_FI, stou(-3.0f), SInt(-3, 32).toUInt, FTYP_S, ITYP_W),
    ConvTest(OP_FCVT_FI, dtou(-3.0), SInt(-3, 64).toUInt, FTYP_D, ITYP_L),
    ConvTest(OP_FCVT_FI, stou(3.0f), UInt(3), FTYP_S, ITYP_WU),
    ConvTest(OP_FCVT_FI, dtou(3.0), UInt(3), FTYP_D, ITYP_LU),
    ConvTest(OP_FCVT_IF, UInt(3), stou(3.0f), FTYP_S, ITYP_W),
    ConvTest(OP_FCVT_IF, UInt(3), dtou(3.0), FTYP_D, ITYP_L),
    ConvTest(OP_FCVT_IF, SInt(-3, 64).toUInt, stou(-3.0f), FTYP_S, ITYP_W),
    ConvTest(OP_FCVT_IF, SInt(-3, 64).toUInt, dtou(-3.0), FTYP_D, ITYP_L),
    ConvTest(OP_FCVT_IF, UInt(3), stou(3.0f), FTYP_S, ITYP_WU),
    ConvTest(OP_FCVT_FF, dtou(3.0), stou(3.0f), FTYP_S, FTYP_D),
    ConvTest(OP_FCVT_FF, stou(3.0f), dtou(3.0), FTYP_D, FTYP_S))

  val requests = Vec(conv_tests.map { test =>
    val req = Wire(new FPRequest)
    req.op := test.op
    req.ftyp := test.ftyp
    req.ityp := test.ityp
    req.in1 := test.in
    req.rm := UInt("b111")
    req
  })

  val expected = Vec(conv_tests.map(_.res))

  def resp_match(resp: UInt, exp: UInt): Bool =
    resp === exp

  def test_name = "Conv"

  elaborate()
}

class FPUTestSuite(implicit p: Parameters) extends GroundTest()(p) {
  disablePorts(fpu = false)

  val tests = Seq(
    Module(new SinglePrecFPUTest),
    Module(new DoublePrecFPUTest),
    Module(new ConvFPUTest))

  val arb = Module(new FPSideArbiter(tests.size))
  arb.io.in <> tests.map(_.io.fpu)
  io.fpu <> arb.io.out

  io.finished := tests.map(_.io.finished).reduce(_ && _)
}
