// See LICENSE for license details.

package chisel3.core

import scala.language.experimental.macros

import chisel3.internal._
import chisel3.internal.Builder.pushCommand
import chisel3.internal.firrtl._
import chisel3.internal.sourceinfo.{SourceInfo, DeprecatedSourceInfo, UnlocatableSourceInfo, WireTransform, SourceInfoTransform}

sealed abstract class Direction(name: String) {
  override def toString: String = name
  def flip: Direction
}
object Direction {
  object Input  extends Direction("input") { override def flip: Direction = Output }
  object Output extends Direction("output") { override def flip: Direction = Input }
}

@deprecated("debug doesn't do anything in Chisel3 as no pruning happens in the frontend", "chisel3")
object debug {  // scalastyle:ignore object.name
  def apply (arg: Data): Data = arg
}

object DataMirror {
  def widthOf(target: Data): Width = target.width
}

/**
* Input, Output, and Flipped are used to define the directions of Module IOs.
*
* Note that they do not currently call target to be a newType or cloneType.
* This is nominally for performance reasons to avoid too many extra copies when
* something is flipped multiple times.
*
* Thus, an error will be thrown if these are used on bound Data
*/
object Input {
  def apply[T<:Data](target: T): T =
    Binding.bind(target, InputBinder, "Error: Cannot set as input ")
}
object Output {
  def apply[T<:Data](target: T): T =
    Binding.bind(target, OutputBinder, "Error: Cannot set as output ")
}
object Flipped {
  def apply[T<:Data](target: T): T =
    Binding.bind(target, FlippedBinder, "Error: Cannot flip ")
}

object Data {
  /**
  * This function returns true if the FIRRTL type of this Data should be flipped
  * relative to other nodes.
  *
  * Note that the current scheme only applies Flip to Elements or Vec chains of
  * Elements.
  *
  * A Bundle is never marked flip, instead preferring its root fields to be marked
  *
  * The Vec check is due to the fact that flip must be factored out of the vec, ie:
  * must have flip field: Vec(UInt) instead of field: Vec(flip UInt)
  */
  private[chisel3] def isFlipped(target: Data): Boolean = target match {
    case (element: Element) => element.binding.direction == Some(Direction.Input)
    case (vec: Vec[Data @unchecked]) => isFlipped(vec.sample_element)
    case (bundle: Bundle) => false
  }

  implicit class AddDirectionToData[T<:Data](val target: T) extends AnyVal {
    @deprecated("Input(Data) should be used over Data.asInput", "gchisel")
    def asInput: T = Input(target)
    @deprecated("Output(Data) should be used over Data.asOutput", "gchisel")
    def asOutput: T = Output(target)
    @deprecated("Flipped(Data) should be used over Data.flip", "gchisel")
    def flip(): T = Flipped(target)
  }
}

/** This forms the root of the type system for wire data types. The data value
  * must be representable as some number (need not be known at Chisel compile
  * time) of bits, and must have methods to pack / unpack structured data to /
  * from bits.
  */
abstract class Data extends HasId {
  // Return ALL elements at root of this type.
  // Contasts with flatten, which returns just Bits
  private[chisel3] def allElements: Seq[Element]

  private[core] def badConnect(that: Data)(implicit sourceInfo: SourceInfo): Unit =
    throwException(s"cannot connect ${this} and ${that}")
  private[chisel3] def connect(that: Data)(implicit sourceInfo: SourceInfo): Unit = {
    Binding.checkSynthesizable(this, s"'this' ($this)")
    Binding.checkSynthesizable(that, s"'that' ($that)")
    try {
      MonoConnect.connect(sourceInfo, this, that, Builder.forcedModule)
    } catch {
      case MonoConnect.MonoConnectException(message) =>
        throwException(
          s"Connection between sink ($this) and source ($that) failed @$message"
        )
    }
  }
  private[chisel3] def bulkConnect(that: Data)(implicit sourceInfo: SourceInfo): Unit = {
    Binding.checkSynthesizable(this, s"'this' ($this)")
    Binding.checkSynthesizable(that, s"'that' ($that)")
    try {
      BiConnect.connect(sourceInfo, this, that, Builder.forcedModule)
    } catch {
      case BiConnect.BiConnectException(message) =>
        throwException(
          s"Connection between left ($this) and source ($that) failed @$message"
        )
    }
  }
  private[chisel3] def lref: Node = Node(this)
  private[chisel3] def ref: Arg = if (isLit) litArg.get else lref
  private[chisel3] def cloneTypeWidth(width: Width): this.type
  private[chisel3] def toType: String

  def cloneType: this.type
  final def := (that: Data)(implicit sourceInfo: SourceInfo): Unit = this connect that
  final def <> (that: Data)(implicit sourceInfo: SourceInfo): Unit = this bulkConnect that
  def litArg(): Option[LitArg] = None
  def litValue(): BigInt = litArg.get.num
  def isLit(): Boolean = litArg.isDefined

  private[core] def width: Width
  final def getWidth: Int = width.get

  // While this being in the Data API doesn't really make sense (should be in
  // Aggregate, right?) this is because of an implementation limitation:
  // cloneWithDirection, which is private and defined here, needs flatten to
  // set element directionality.
  // Related: directionality is mutable state. A possible solution for both is
  // to define directionality relative to the container, but these parent links
  // currently don't exist (while this information may be available during
  // FIRRTL emission, it would break directionality querying from Chisel, which
  // does get used).
  private[chisel3] def flatten: IndexedSeq[Bits]

  /** Creates an new instance of this type, unpacking the input Bits into
    * structured data.
    *
    * This performs the inverse operation of toBits.
    *
    * @note does NOT assign to the object this is called on, instead creates
    * and returns a NEW object (useful in a clone-and-assign scenario)
    * @note does NOT check bit widths, may drop bits during assignment
    * @note what fromBits assigs to must have known widths
    */
  def fromBits(that: Bits): this.type = macro SourceInfoTransform.thatArg

  def do_fromBits(that: Bits)(implicit sourceInfo: SourceInfo): this.type = {
    var i = 0
    val wire = Wire(this.cloneType)
    val bits =
      if (that.width.known && that.width.get >= wire.width.get) {
        that
      } else {
        Wire(that.cloneTypeWidth(wire.width), init = that)
      }
    for (x <- wire.flatten) {
      x := bits(i + x.getWidth-1, i)
      i += x.getWidth
    }
    wire.asInstanceOf[this.type]
  }

  /** Packs the value of this object as plain Bits.
    *
    * This performs the inverse operation of fromBits(Bits).
    */
  @deprecated("Use asBits, which makes the reinterpret cast more explicit and actually returns Bits", "chisel3")
  def toBits(): UInt = SeqUtils.do_asUInt(this.flatten)(DeprecatedSourceInfo)
}

object Wire {
  def apply[T <: Data](t: T): T = macro WireTransform.apply[T]

  // No source info since Scala macros don't yet support named / default arguments.
  def apply[T <: Data](dummy: Int = 0, init: T): T =
    do_apply(null.asInstanceOf[T], init)(UnlocatableSourceInfo)

  // No source info since Scala macros don't yet support named / default arguments.
  def apply[T <: Data](t: T, init: T): T =
    do_apply(t, init)(UnlocatableSourceInfo)

  def do_apply[T <: Data](t: T, init: T)(implicit sourceInfo: SourceInfo): T = {
    val x = Reg.makeType(t, null.asInstanceOf[T], init)

    // Bind each element of x to being a Wire
    Binding.bind(x, WireBinder(Builder.forcedModule), "Error: t")

    pushCommand(DefWire(sourceInfo, x))
    pushCommand(DefInvalid(sourceInfo, x.ref))
    if (init != null) {
      Binding.checkSynthesizable(init, s"'init' ($init)")
      x := init
    }

    x
  }
}

object Clock {
  def apply(): Clock = new Clock
}

// TODO: Document this.
sealed class Clock extends Element(Width(1)) {
  def cloneType: this.type = Clock().asInstanceOf[this.type]
  private[chisel3] override def flatten: IndexedSeq[Bits] = IndexedSeq()
  private[chisel3] def cloneTypeWidth(width: Width): this.type = cloneType
  private[chisel3] def toType = "Clock"

  override def connect (that: Data)(implicit sourceInfo: SourceInfo): Unit = that match {
    case _: Clock => super.connect(that)(sourceInfo)
    case _ => super.badConnect(that)(sourceInfo)
  }
}
