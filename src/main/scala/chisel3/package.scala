// See LICENSE for license details.

package object chisel3 {    // scalastyle:ignore package.object.name
  import scala.language.experimental.macros

  import internal.firrtl.{Width, NumericBound}
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

  trait UIntFactory extends chisel3.core.UIntFactory {
    /** Create a UInt literal with inferred width. */
    @deprecated("chisel3, will be removed by end of 2016, use n.U")
    def apply(n: String): UInt = Lit(chisel3.core.fromStringToLiteral.parse(n),
        chisel3.core.fromStringToLiteral.parsedWidth(n))
    /** Create a UInt literal with fixed width. */
    @deprecated("chisel3, will be removed by end of 2016, use n.U(width.W)")
    def apply(n: String, width: Int): UInt = Lit(chisel3.core.fromStringToLiteral.parse(n),
        Width(width))

    /** Create a UInt literal with specified width. */
    @deprecated("chisel3, will be removed by end of 2016, use value.U(width)")
    def apply(value: BigInt, width: Width): UInt = Lit(value, width)

    /** Create a UInt literal with fixed width. */
    @deprecated("chisel3, will be removed by end of 2016, use value.U(width.W)")
    def apply(value: BigInt, width: Int): UInt = Lit(value, Width(width))

    /** Create a UInt with a specified width - compatibility with Chisel2. */
    @deprecated("chisel3, will be removed by end of 2016, use UInt(width.W)")
    def apply(dir: Option[Direction] = None, width: Int): UInt = apply(Width(width))

    /** Create a UInt literal with inferred width.- compatibility with Chisel2. */
    @deprecated("chisel3, will be removed by end of 2016, use value.U")
    def apply(value: BigInt): UInt = apply(value, Width())

    /** Create a UInt with a specified width */
    @deprecated("chisel3, will be removed by end of 2016, use UInt(width.W)")
    def width(width: Int): UInt = apply(Width(width))

    /** Create a UInt port with specified width. */
    @deprecated("chisel3, will be removed by end of 2016, use UInt(width)")
    def width(width: Width): UInt = apply(width)
  }

  trait SIntFactory extends chisel3.core.SIntFactory {
    /** Create a SInt type or port with fixed width. */
    @deprecated("chisel3, will be removed by end of 2016, use SInt(width.W)")
    def width(width: Int): SInt = apply(Width(width))
    /** Create an SInt type with specified width. */
    @deprecated("chisel3, will be removed by end of 2016, use SInt(width)")
    def width(width: Width): SInt = apply(width)

    /** Create an SInt literal with inferred width. */
    @deprecated("chisel3, will be removed by end of 2016, use value.S")
    def apply(value: BigInt): SInt = Lit(value)
    /** Create an SInt literal with fixed width. */
    @deprecated("chisel3, will be removed by end of 2016, use value.S(width.W)")
    def apply(value: BigInt, width: Int): SInt = Lit(value, width)

    /** Create an SInt literal with specified width. */
    @deprecated("chisel3, will be removed by end of 2016, use value.S(width)")
    def apply(value: BigInt, width: Width): SInt = Lit(value, width)

    @deprecated("chisel3, will be removed by end of 2016, use value.S")
    def Lit(value: BigInt): SInt = Lit(value, Width())

    @deprecated("chisel3, will be removed by end of 2016, use value.S(width)")
    def Lit(value: BigInt, width: Int): SInt = Lit(value, Width(width))
  }

  trait BoolFactory extends chisel3.core.BoolFactory {
    /** Creates Bool literal.
     */
    @deprecated("chisel3, will be removed by end of 2016, use x.B")
    def apply(x: Boolean): Bool = Lit(x)
  }

  object Bits extends UIntFactory
  type Num[T <: Data] = chisel3.core.Num[T]
  type UInt = chisel3.core.UInt
  object UInt extends UIntFactory
  type SInt = chisel3.core.SInt
  object SInt extends SIntFactory
  type FixedPoint = chisel3.core.FixedPoint
  val FixedPoint = chisel3.core.FixedPoint
  type Bool = chisel3.core.Bool
  object Bool extends BoolFactory
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

  type Printable = chisel3.core.Printable
  val Printable = chisel3.core.Printable
  type Printables = chisel3.core.Printables
  val Printables = chisel3.core.Printables
  type PString = chisel3.core.PString
  val PString = chisel3.core.PString
  type FirrtlFormat = chisel3.core.FirrtlFormat
  val FirrtlFormat = chisel3.core.FirrtlFormat
  type Decimal = chisel3.core.Decimal
  val Decimal = chisel3.core.Decimal
  type Hexadecimal = chisel3.core.Hexadecimal
  val Hexadecimal = chisel3.core.Hexadecimal
  type Binary = chisel3.core.Binary
  val Binary = chisel3.core.Binary
  type Character = chisel3.core.Character
  val Character = chisel3.core.Character
  type Name = chisel3.core.Name
  val Name = chisel3.core.Name
  type FullName = chisel3.core.FullName
  val FullName = chisel3.core.FullName
  val Percent = chisel3.core.Percent

  /** Implicit for custom Printable string interpolator */
  implicit class PrintableHelper(val sc: StringContext) extends AnyVal {
    /** Custom string interpolator for generating Printables: p"..."
      * Will call .toString on any non-Printable arguments (mimicking s"...")
      */
    def p(args: Any*): Printable = {
      sc.checkLengths(args) // Enforce sc.parts.size == pargs.size + 1
      val pargs: Seq[Option[Printable]] = args map {
        case p: Printable => Some(p)
        case d: Data => Some(d.toPrintable)
        case any => for {
          v <- Option(any) // Handle null inputs
          str = v.toString
          if !str.isEmpty // Handle empty Strings
        } yield PString(str)
      }
      val parts = sc.parts map StringContext.treatEscapes
      // Zip sc.parts and pargs together ito flat Seq
      // eg. Seq(sc.parts(0), pargs(0), sc.parts(1), pargs(1), ...)
      val seq = for { // append None because sc.parts.size == pargs.size + 1
        (literal, arg) <- parts zip (pargs :+ None)
        optPable <- Seq(Some(PString(literal)), arg)
        pable <- optPable // Remove Option[_]
      } yield pable
      Printables(seq)
    }
  }

  implicit def string2Printable(str: String): Printable = PString(str)

  implicit class fromtIntToLiteral(override val x: Int) extends chisel3.core.fromIntToLiteral(x)
  implicit class fromBigIntToLiteral(override val x: BigInt) extends chisel3.core.fromBigIntToLiteral(x)
  implicit class fromStringToLiteral(override val x: String) extends chisel3.core.fromStringToLiteral(x)
  implicit class fromBooleanToLiteral(override val x: Boolean) extends chisel3.core.fromBooleanToLiteral(x)
  implicit class fromDoubleToLiteral(override val x: Double) extends chisel3.core.fromDoubleToLiteral(x)
  implicit class fromIntToWidth(override val x: Int) extends chisel3.core.fromIntToWidth(x)

  implicit class fromUIntToBitPatComparable(val x: UInt) extends AnyVal {
    final def === (that: BitPat): Bool = macro SourceInfoTransform.thatArg
    @deprecated("Use '=/=', which avoids potential precedence problems", "chisel3")
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

  // Debugger/Tester access to internal Chisel data structures and methods.
  def getDataElements(a: Aggregate): Seq[Element] = {
    a.allElements
  }
  def getModulePorts(m: Module): Seq[Port] = m.getPorts
  def getFirrtlDirection(d: Data): Direction = chisel3.core.Data.getFirrtlDirection(d)

  /** Package for experimental features, which may have their API changed, be removed, etc.
    *
    * Because its contents won't necessarily have the same level of stability and support as
    * non-experimental, you must explicitly import this package to use its contents.
    */
  object experimental {
    type Param = chisel3.core.Param
    type IntParam = chisel3.core.IntParam
    val IntParam = chisel3.core.IntParam
    type DoubleParam = chisel3.core.DoubleParam
    val DoubleParam = chisel3.core.DoubleParam
    type StringParam = chisel3.core.StringParam
    val StringParam = chisel3.core.StringParam
    type RawParam = chisel3.core.RawParam
    val RawParam = chisel3.core.RawParam

    // Implicit conversions for BlackBox Parameters
    implicit def fromIntToIntParam(x: Int): IntParam = IntParam(BigInt(x))
    implicit def fromLongToIntParam(x: Long): IntParam = IntParam(BigInt(x))
    implicit def fromBigIntToIntParam(x: BigInt): IntParam = IntParam(x)
    implicit def fromDoubleToDoubleParam(x: Double): DoubleParam = DoubleParam(x)
    implicit def fromStringToStringParam(x: String): StringParam = StringParam(x)

    implicit class ChiselRange(val sc: StringContext) extends AnyVal {
      /** Specifies a range using mathematical range notation. Variables can be interpolated using
       *  standard string interpolation syntax.
        * @example {{{
        * UInt(range"[0, 2)")
        * UInt(range"[0, $myInt)")
        * UInt(range"[0, ${myInt + 2})")
        * }}}
        */
      def range(args: Any*): (NumericBound[Int], NumericBound[Int]) = macro chisel3.internal.RangeTransform.apply
    }
  }
}
