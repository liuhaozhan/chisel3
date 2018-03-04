package ChiselTests
import Chisel._

class Pads extends Module {
  val io = new Bundle {
    val a   = Bits(INPUT,  4)
    val asp = Bits(OUTPUT, 8)
    val aup = Bits(OUTPUT, 8)
  }
  io.asp := io.a.toSInt
  io.aup := io.a.toUInt
}

class PadsTester(c: Pads) extends Tester(c) {
  def pads(x: BigInt, s: Int, w: Int) = {
    val sign  = (x & (1 << (s-1)))
    val wmask = (1 << w) - 1
    val bmask = (1 << s) - 1
    if (sign == 0) x else ((~bmask | x) & wmask)
  }
  for (t <- 0 until 16) {
    val test_a = rnd.nextInt(1 << 4)
    poke(c.io.a, test_a)
    step(1)
    expect(c.io.asp, pads(test_a, 4, 8))
    expect(c.io.aup, test_a)
  }
}
