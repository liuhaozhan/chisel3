// See LICENSE for license details.

package object chisel3 {    // scalastyle:ignore package.object.name
  import scala.language.experimental.macros

  import internal.firrtl.Width
  import internal.sourceinfo.{SourceInfo, SourceInfoTransform}
  import util.BitPat

  import chisel3.core.{Binding, FlippedBinder}
  import chisel3.util._
  import chisel3.internal.firrtl.Port

  type Direction = chisel3.core.Direction
  val Input   = chisel3.core.Input
  val Output  = chisel3.core.Output
  val Flipped = chisel3.core.Flipped

  type Data = chisel3.core.Data
  val Wire = chisel3.core.Wire
  val Clock = chisel3.core.Clock
  type Clock = chisel3.core.Clock

  type Aggregate = chisel3.core.Aggregate
  val Vec = chisel3.core.Vec
  type Vec[T <: Data] = chisel3.core.Vec[T]
  type VecLike[T <: Data] = chisel3.core.VecLike[T]
  type Bundle = chisel3.core.Bundle

  val assert = chisel3.core.assert

  type Element = chisel3.core.Element
  type Bits = chisel3.core.Bits
  val Bits = chisel3.core.Bits
  type Num[T <: Data] = chisel3.core.Num[T]
  type UInt = chisel3.core.UInt
  val UInt = chisel3.core.UInt
  type SInt = chisel3.core.SInt
  val SInt = chisel3.core.SInt
  type Bool = chisel3.core.Bool
  val Bool = chisel3.core.Bool
  val Mux = chisel3.core.Mux

  type BlackBox = chisel3.core.BlackBox

  val Mem = chisel3.core.Mem
  type MemBase[T <: Data] = chisel3.core.MemBase[T]
  type Mem[T <: Data] = chisel3.core.Mem[T]
  val SeqMem = chisel3.core.SeqMem
  type SeqMem[T <: Data] = chisel3.core.SeqMem[T]

  val Module = chisel3.core.Module
  type Module = chisel3.core.Module

  val printf = chisel3.core.printf

  val Reg = chisel3.core.Reg

  val when = chisel3.core.when
  type WhenContext = chisel3.core.WhenContext

  /**
  * These implicit classes allow one to convert scala.Int|scala.BigInt to
  * Chisel.UInt|Chisel.SInt by calling .asUInt|.asSInt on them, respectively.
  * The versions .asUInt(width)|.asSInt(width) are also available to explicitly
  * mark a width for the new literal.
  *
  * Also provides .asBool to scala.Boolean and .asUInt to String
  *
  * Note that, for stylistic reasons, one should avoid extracting immediately
  * after this call using apply, ie. 0.asUInt(1)(0) due to potential for
  * confusion (the 1 is a bit length and the 0 is a bit extraction position).
  * Prefer storing the result and then extracting from it.
  */
  implicit class fromIntToLiteral(val x: Int) extends AnyVal {
    def U: UInt = UInt(BigInt(x), Width())    // scalastyle:ignore method.name
    def S: SInt = SInt(BigInt(x), Width())    // scalastyle:ignore method.name

    def asUInt(): UInt = UInt(x, Width())
    def asSInt(): SInt = SInt(x, Width())
    def asUInt(width: Int): UInt = UInt(x, width)
    def asSInt(width: Int): SInt = SInt(x, width)
  }

  implicit class fromBigIntToLiteral(val x: BigInt) extends AnyVal {
    def U: UInt = UInt(x, Width())    // scalastyle:ignore method.name
    def S: SInt = SInt(x, Width())    // scalastyle:ignore method.name

    def asUInt(): UInt = UInt(x, Width())
    def asSInt(): SInt = SInt(x, Width())
    def asUInt(width: Int): UInt = UInt(x, width)
    def asSInt(width: Int): SInt = SInt(x, width)
  }
  implicit class fromStringToLiteral(val x: String) extends AnyVal {
    def U: UInt = UInt(x)    // scalastyle:ignore method.name
  }
  implicit class fromBooleanToLiteral(val x: Boolean) extends AnyVal {
    def B: Bool = Bool(x)    // scalastyle:ignore method.name
  }

  implicit class fromUIntToBitPatComparable(val x: UInt) extends AnyVal {
    final def === (that: BitPat): Bool = macro SourceInfoTransform.thatArg
    final def != (that: BitPat): Bool = macro SourceInfoTransform.thatArg
    final def =/= (that: BitPat): Bool = macro SourceInfoTransform.thatArg

    def do_=== (that: BitPat)(implicit sourceInfo: SourceInfo): Bool = that === x    // scalastyle:ignore method.name
    def do_!= (that: BitPat)(implicit sourceInfo: SourceInfo): Bool = that != x      // scalastyle:ignore method.name
    def do_=/= (that: BitPat)(implicit sourceInfo: SourceInfo): Bool = that =/= x    // scalastyle:ignore method.name
  }

  // Compatibility with existing code.
  val INPUT = chisel3.core.Direction.Input
  val OUTPUT = chisel3.core.Direction.Output
  val NODIR = chisel3.core.Direction.Unspecified
  type ChiselException = chisel3.internal.ChiselException
  type ValidIO[+T <: Data] = chisel3.util.Valid[T]
  val ValidIO = chisel3.util.Valid
  val Decoupled = chisel3.util.DecoupledIO

  class EnqIO[+T <: Data](gen: T) extends DecoupledIO(gen) {
    def init(): Unit = {
      this.noenq()
    }
    override def cloneType: this.type = EnqIO(gen).asInstanceOf[this.type]
  }
  class DeqIO[+T <: Data](gen: T) extends DecoupledIO(gen) {
    val Data = chisel3.core.Data
    Data.setFirrtlDirection(this, Data.getFirrtlDirection(this).flip)
    Binding.bind(this, FlippedBinder, "Error: Cannot flip ")
    def init(): Unit = {
      this.nodeq()
    }
    override def cloneType: this.type = DeqIO(gen).asInstanceOf[this.type]
  }
  object EnqIO {
    def apply[T<:Data](gen: T): EnqIO[T] = new EnqIO(gen)
  }
  object DeqIO {
    def apply[T<:Data](gen: T): DeqIO[T] = new DeqIO(gen)
  }

  // Debugger/Tester access to internal Chisel data structures and methods.
  def getDataElements(a: Aggregate): Seq[Element] = {
    a.allElements
  }
  def getModulePorts(m: Module): Seq[Port] = m.getPorts
}
