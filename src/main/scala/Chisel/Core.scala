package Chisel
import scala.collection.immutable.ListMap
import scala.collection.mutable.{ArrayBuffer, HashSet, LinkedHashMap}
import java.lang.reflect.Modifier._
import java.lang.Double.longBitsToDouble
import java.lang.Float.intBitsToFloat
import Builder.pushCommand
import Builder.pushOp
import Builder.dynamicContext
import PrimOp._

object Literal {
  def sizeof(x: BigInt): Int = x.bitLength

  def decodeBase(base: Char): Int = base match {
    case 'x' | 'h' => 16
    case 'd' => 10
    case 'o' => 8
    case 'b' => 2
    case _ => ChiselError.error("Invalid base " + base); 2
  }

  def stringToVal(base: Char, x: String): BigInt =
    BigInt(x, decodeBase(base))
}

sealed abstract class Direction(name: String) {
  override def toString = name
  def flip: Direction
}
object INPUT  extends Direction("input") { def flip = OUTPUT }
object OUTPUT extends Direction("output") { def flip = INPUT }
object NO_DIR extends Direction("?") { def flip = NO_DIR }

object debug {
  // TODO:
  def apply (arg: Data) = arg
}

abstract class Data(dirArg: Direction) extends HasId {
  private[Chisel] val _mod: Module = dynamicContext.getCurrentModule.getOrElse(null)
  if (_mod ne null)
    _mod.addNode(this)

  def toType: Kind
  def dir: Direction = dirVar

  // Sucks this is mutable state, but cloneType doesn't take a Direction arg
  private var isFlipVar = dirArg == INPUT
  private var dirVar = dirArg
  private[Chisel] def isFlip = isFlipVar

  private def cloneWithDirection(newDir: Direction => Direction,
                                 newFlip: Boolean => Boolean): this.type = {
    val res = this.cloneType
    res.isFlipVar = newFlip(res.isFlipVar)
    for ((me, it) <- this.flatten zip res.flatten)
      (it: Data).dirVar = newDir((me: Data).dirVar)
    res
  }
  def asInput: this.type = cloneWithDirection(_ => INPUT, _ => true)
  def asOutput: this.type = cloneWithDirection(_ => OUTPUT, _ => false)
  def flip(): this.type = cloneWithDirection(_.flip, !_)

  private[Chisel] def badConnect(that: Data): Unit =
    throwException(s"cannot connect ${this} and ${that}")
  private[Chisel] def connect(that: Data): Unit =
    pushCommand(Connect(this.lref, that.ref))
  private[Chisel] def bulkConnect(that: Data): Unit =
    pushCommand(BulkConnect(this.lref, that.lref))
  private[Chisel] def collectElts: Unit = { }
  private[Chisel] def lref: Alias = Alias(this)
  private[Chisel] def ref: Arg = if (isLit) litArg.get else lref
  private[Chisel] def cloneTypeWidth(width: Width): this.type

  def := (that: Data): Unit = this badConnect that
  def <> (that: Data): Unit = this badConnect that
  def cloneType: this.type
  def litArg(): Option[LitArg] = None
  def litValue(): BigInt = litArg.get.num
  def isLit(): Boolean = litArg.isDefined
  def floLitValue: Float = intBitsToFloat(litValue().toInt)
  def dblLitValue: Double = longBitsToDouble(litValue().toLong)

  def width: Width
  final def getWidth = width.get

  def flatten: IndexedSeq[UInt]
  def fromBits(n: Bits): this.type = {
    var i = 0
    val wire = Wire(this.cloneType)
    for (x <- wire.flatten) {
      x := n(i + x.getWidth-1, i)
      i += x.getWidth
    }
    wire.asInstanceOf[this.type]
  }
  def toBits(): UInt = {
    val elts = this.flatten.reverse
    Cat(elts.head, elts.tail:_*)
  }

  def toPort: Port = Port(this, toType)
}

object Wire {
  def apply[T <: Data](t: T = null, init: T = null): T = {
    val x = Reg.makeType(t, null.asInstanceOf[T], init)
    pushCommand(DefWire(x, x.toType))
    if (init != null)
      x := init
    else
      x.flatten.foreach(e => e := e.fromInt(0))
    x
  }
}

object Reg {
  private[Chisel] def makeType[T <: Data](t: T = null, next: T = null, init: T = null): T = {
    if (t ne null) t.cloneType
    else if (next ne null) next.cloneTypeWidth(Width())
    else if (init ne null) init.litArg match {
      // For e.g. Reg(init=UInt(0, k)), fix the Reg's width to k
      case Some(lit) if lit.forcedWidth => init.cloneType
      case _ => init.cloneTypeWidth(Width())
    } else throwException("cannot infer type")
  }

  def apply[T <: Data](t: T = null, next: T = null, init: T = null): T = {
    val x = makeType(t, next, init)
    pushCommand(DefRegister(x, x.toType, Alias(x._mod.clock), Alias(x._mod.reset))) // TODO multi-clock
    if (init != null)
      pushCommand(ConnectInit(x.lref, init.ref))
    if (next != null) 
      x := next
    x
  }
  def apply[T <: Data](outType: T): T = Reg[T](outType, null.asInstanceOf[T], null.asInstanceOf[T])
}

object Mem {
  def apply[T <: Data](t: T, size: Int): Mem[T] = {
    val mt  = t.cloneType
    val mem = new Mem(mt, size)
    pushCommand(DefMemory(mem, mt.toType, size, Alias(mt._mod.clock))) // TODO multi-clock
    mem
  }
}

class Mem[T <: Data](t: T, val length: Int) extends Aggregate(NO_DIR) with VecLike[T] {
  def apply(idx: Int): T = apply(UInt(idx))
  def apply(idx: UInt): T = {
    val x = t.cloneType
    pushCommand(DefAccessor(x, Alias(this), NO_DIR, idx.ref))
    x
  }

  def read(idx: UInt): T = apply(idx)
  def write(idx: UInt, data: T): Unit = apply(idx) := data
  def write(idx: UInt, data: T, mask: T): Unit = {
    // This is totally fucked, but there's no true write mask support yet
    val mask1 = mask.toBits
    write(idx, t.fromBits((read(idx).toBits & ~mask1) | (data.toBits & mask1)))
  }

  def cloneType = throwException("Mem.cloneType unimplemented")
  def flatten = throwException("Mem.flatten unimplemented")
  def toType = throwException("Mem.toType unimplemented")
}

object SeqMem {
  def apply[T <: Data](t: T, size: Int): SeqMem[T] =
    new SeqMem(t, size)
}

// For now, implement SeqMem in terms of Mem
class SeqMem[T <: Data](t: T, n: Int) {
  private val mem = Mem(t, n)

  def read(addr: UInt): T = mem.read(Reg(next = addr))
  def read(addr: UInt, enable: Bool): T = mem.read(RegEnable(addr, enable))

  def write(addr: UInt, data: T): Unit = mem.write(addr, data)
  def write(addr: UInt, data: T, mask: T): Unit = mem.write(addr, data, mask)
}

object Vec {
  def apply[T <: Data](gen: T, n: Int): Vec[T] = {
    if (gen.isLit) apply(Seq.fill(n)(gen))
    else new Vec(gen.cloneType, n)
  }
  def apply[T <: Data](elts: Seq[T]): Vec[T] = {
    require(!elts.isEmpty)
    val width = elts.map(_.width).reduce(_ max _)
    val vec = new Vec(elts.head.cloneTypeWidth(width), elts.length)
    pushCommand(DefWire(vec, vec.toType))
    for ((v, e) <- vec zip elts)
      v := e
    vec
  }
  def apply[T <: Data](elt0: T, elts: T*): Vec[T] =
    apply(elt0 +: elts.toSeq)
  def tabulate[T <: Data](n: Int)(gen: (Int) => T): Vec[T] = 
    apply((0 until n).map(i => gen(i)))
  def fill[T <: Data](n: Int)(gen: => T): Vec[T] = 
    apply(gen, n)
}

abstract class Aggregate(dirArg: Direction) extends Data(dirArg) {
  def cloneTypeWidth(width: Width): this.type = cloneType
  def width: Width = flatten.map(_.width).reduce(_ + _)
}

class Vec[T <: Data](gen: => T, val length: Int)
    extends Aggregate(gen.dir) with VecLike[T] {
  private val self = IndexedSeq.fill(length)(gen)

  override def collectElts: Unit =
    for ((elt, i) <- self zipWithIndex)
      elt.setRef(this, i)

  override def <> (that: Data): Unit = that match {
    case _: Vec[_] => this bulkConnect that
    case _ => this badConnect that
  }

  def <> (that: Seq[T]): Unit =
    for ((a, b) <- this zip that)
      a <> b

  def <> (that: Vec[T]): Unit = this bulkConnect that

  override def := (that: Data): Unit = that match {
    case _: Vec[_] => this connect that
    case _ => this badConnect that
  }

  def := (that: Seq[T]): Unit = {
    require(this.length == that.length)
    for ((a, b) <- this zip that)
      a := b
  }

  def := (that: Vec[T]): Unit = this connect that

  def apply(idx: UInt): T = {
    val x = gen
    pushCommand(DefAccessor(x, Alias(this), NO_DIR, idx.ref))
    x
  }

  def apply(idx: Int): T = self(idx)

  def toType: Kind = VectorType(length, gen.toType, isFlip)

  override def cloneType: this.type =
    Vec(gen, length).asInstanceOf[this.type]

  override lazy val flatten: IndexedSeq[UInt] =
    (0 until length).flatMap(i => this.apply(i).flatten)

  def read(idx: UInt): T = apply(idx)
  def write(idx: UInt, data: T): Unit = apply(idx) := data
}

trait VecLike[T <: Data] extends collection.IndexedSeq[T] {
  def read(idx: UInt): T
  def write(idx: UInt, data: T): Unit
  def apply(idx: UInt): T

  def forall(p: T => Bool): Bool = (this map p).fold(Bool(true))(_&&_)
  def exists(p: T => Bool): Bool = (this map p).fold(Bool(false))(_||_)
  def contains(x: T) (implicit evidence: T <:< UInt): Bool = this.exists(_ === x)
  def count(p: T => Bool): UInt = PopCount((this map p).toSeq)

  private def indexWhereHelper(p: T => Bool) = this map p zip (0 until length).map(i => UInt(i))
  def indexWhere(p: T => Bool): UInt = PriorityMux(indexWhereHelper(p))
  def lastIndexWhere(p: T => Bool): UInt = PriorityMux(indexWhereHelper(p).reverse)
  def onlyIndexWhere(p: T => Bool): UInt = Mux1H(indexWhereHelper(p))
}

object BitPat {
  private def parse(x: String): (BigInt, BigInt, Int) = {
    require(x.head == 'b', "BINARY BitPats ONLY")
    var bits = BigInt(0)
    var mask = BigInt(0)
    for (d <- x.tail) {
      if (d != '_') {
        if (!"01?".contains(d)) ChiselError.error({"Literal: " + x + " contains illegal character: " + d})
        mask = (mask << 1) + (if (d == '?') 0 else 1)
        bits = (bits << 1) + (if (d == '1') 1 else 0)
      }
    }
    (bits, mask, x.length-1)
  }

  def apply(n: String): BitPat = {
    val (bits, mask, width) = parse(n)
    new BitPat(bits, mask, width)
  }

  def DC(width: Int): BitPat = BitPat("b" + ("?" * width))

  // BitPat <-> UInt
  implicit def BitPatToUInt(x: BitPat): UInt = {
    require(x.mask == (BigInt(1) << x.getWidth)-1)
    UInt(x.value, x.getWidth)
  }
  implicit def apply(x: UInt): BitPat = {
    require(x.isLit)
    BitPat("b" + x.litValue.toString(2))
  }
}

class BitPat(val value: BigInt, val mask: BigInt, width: Int) {
  def getWidth: Int = width
  def === (other: UInt): Bool = UInt(value) === (other & UInt(mask))
  def != (other: UInt): Bool = !(this === other)
}

abstract class Element(dirArg: Direction, val width: Width) extends Data(dirArg) {
  def flatten: IndexedSeq[UInt] = IndexedSeq(toBits)
}

object Clock {
  def apply(dir: Direction = NO_DIR): Clock = new Clock(dir)
}

sealed class Clock(dirArg: Direction) extends Element(dirArg, Width(1)) {
  def cloneType: this.type = Clock(dirArg).asInstanceOf[this.type]
  def cloneTypeWidth(width: Width): this.type = cloneType
  override def flatten: IndexedSeq[UInt] = IndexedSeq()
  def toType: Kind = ClockType(isFlip)

  override def := (that: Data): Unit = that match {
    case _: Clock => this connect that
    case _ => this badConnect that
  }
}

sealed abstract class Bits(dirArg: Direction, width: Width, override val litArg: Option[LitArg]) extends Element(dirArg, width) {
  def fromInt(x: BigInt): this.type
  def makeLit(value: BigInt): LitArg
  def cloneType: this.type = cloneTypeWidth(width)

  override def <> (that: Data): Unit = this := that

  final def apply(x: BigInt): Bool =
    if (isLit()) Bool((litValue() >> x.toInt) & 1)
    else pushOp(DefPrim(Bool(), BitSelectOp, this.ref, ILit(x)))
  final def apply(x: Int): Bool =
    apply(BigInt(x))
  final def apply(x: UInt): Bool =
    (this >> x)(0)

  final def apply(x: BigInt, y: BigInt): UInt = {
    val w = (x - y + 1).toInt
    if (isLit()) UInt((litValue >> y.toInt) & ((BigInt(1) << w) - 1), w)
    else pushOp(DefPrim(UInt(width = w), BitsExtractOp, this.ref, ILit(x), ILit(y)))
  }
  final def apply(x: Int, y: Int): UInt =
    apply(BigInt(x), BigInt(y))

  private[Chisel] def unop[T <: Data](dest: T, op: PrimOp): T =
    pushOp(DefPrim(dest, op, this.ref))
  private[Chisel] def binop[T <: Data](dest: T, op: PrimOp, other: BigInt): T =
    pushOp(DefPrim(dest, op, this.ref, ILit(other)))
  private[Chisel] def binop[T <: Data](dest: T, op: PrimOp, other: Bits): T =
    pushOp(DefPrim(dest, op, this.ref, other.ref))
  private[Chisel] def compop(op: PrimOp, other: Bits): Bool =
    pushOp(DefPrim(Bool(), op, this.ref, other.ref))
  private[Chisel] def redop(op: PrimOp): Bool =
    pushOp(DefPrim(Bool(), op, this.ref))

  def unary_~ : this.type = unop(cloneTypeWidth(width), BitNotOp)
  def pad (other: Int): this.type = binop(cloneTypeWidth(this.width max Width(other)), PadOp, other)

  def << (other: BigInt): Bits
  def << (other: Int): Bits
  def << (other: UInt): Bits
  def >> (other: BigInt): Bits
  def >> (other: Int): Bits
  def >> (other: UInt): Bits

  def toBools: Vec[Bool] = Vec.tabulate(this.getWidth)(i => this(i))

  def asSInt(): SInt
  def asUInt(): UInt
  final def toSInt(): SInt = asSInt
  final def toUInt(): UInt = asUInt

  def toBool(): Bool = width match {
    case KnownWidth(1) => this(0)
    case _ => throwException(s"can't covert UInt<$width> to Bool")
  }

  override def toBits = asUInt
  override def fromBits(n: Bits): this.type = {
    val res = Wire(this).asInstanceOf[this.type]
    res := n
    res
  }
}

abstract trait Num[T <: Data] {
  // def << (b: T): T;
  // def >> (b: T): T;
  //def unary_-(): T;
  def +  (b: T): T;
  def *  (b: T): T;
  def /  (b: T): T;
  def %  (b: T): T;
  def -  (b: T): T;
  def <  (b: T): Bool;
  def <= (b: T): Bool;
  def >  (b: T): Bool;
  def >= (b: T): Bool;

  def min(b: T): T = Mux(this < b, this.asInstanceOf[T], b)
  def max(b: T): T = Mux(this < b, b, this.asInstanceOf[T])
}

sealed class UInt(dir: Direction, width: Width, lit: Option[ULit] = None) extends Bits(dir, width, lit) with Num[UInt] {
  override def cloneTypeWidth(w: Width): this.type =
    new UInt(dir, w).asInstanceOf[this.type]

  def toType: Kind = UIntType(width, isFlip)

  def fromInt(value: BigInt): this.type = UInt(value).asInstanceOf[this.type]
  def makeLit(value: BigInt): ULit = ULit(value, Width())

  override def := (that: Data): Unit = that match {
    case _: UInt => this connect that
    case _ => this badConnect that
  }

  def unary_- = UInt(0) - this
  def unary_-% = UInt(0) -% this
  def +& (other: UInt): UInt = binop(UInt(NO_DIR, (this.width max other.width) + 1), AddOp, other)
  def + (other: UInt): UInt = this +% other
  def +% (other: UInt): UInt = binop(UInt(NO_DIR, this.width max other.width), AddModOp, other)
  def -& (other: UInt): UInt = binop(UInt(NO_DIR, (this.width max other.width) + 1), SubOp, other)
  def - (other: UInt): UInt = this -% other
  def -% (other: UInt): UInt = binop(UInt(NO_DIR, this.width max other.width), SubModOp, other)
  def * (other: UInt): UInt = binop(UInt(NO_DIR, this.width + other.width), TimesOp, other)
  def * (other: SInt): SInt = other * this
  def / (other: UInt): UInt = binop(UInt(NO_DIR, this.width), DivideOp, other)
  def % (other: UInt): UInt = binop(UInt(NO_DIR, this.width), ModOp, other)

  def & (other: UInt): UInt = binop(UInt(NO_DIR, this.width max other.width), BitAndOp, other)
  def | (other: UInt): UInt = binop(UInt(NO_DIR, this.width max other.width), BitOrOp, other)
  def ^ (other: UInt): UInt = binop(UInt(NO_DIR, this.width max other.width), BitXorOp, other)
  def ## (other: UInt): UInt = Cat(this, other)

  def orR = this != UInt(0)
  def andR = ~this === UInt(0)
  def xorR = redop(XorReduceOp)

  def < (other: UInt): Bool = compop(LessOp, other)
  def > (other: UInt): Bool = compop(GreaterOp, other)
  def <= (other: UInt): Bool = compop(LessEqOp, other)
  def >= (other: UInt): Bool = compop(GreaterEqOp, other)
  def != (other: UInt): Bool = compop(NotEqualOp, other)
  def === (other: UInt): Bool = compop(EqualOp, other)
  def unary_! : Bool = this === Bits(0)

  def << (other: Int): UInt = binop(UInt(NO_DIR, this.width + other), ShiftLeftOp, other)
  def << (other: BigInt): UInt = this << other.toInt
  def << (other: UInt): UInt = binop(UInt(NO_DIR, this.width.dynamicShiftLeft(other.width)), DynamicShiftLeftOp, other)
  def >> (other: Int): UInt = binop(UInt(NO_DIR, this.width.shiftRight(other)), ShiftRightOp, other)
  def >> (other: BigInt): UInt = this >> other.toInt
  def >> (other: UInt): UInt = binop(UInt(NO_DIR, this.width), DynamicShiftRightOp, other)

  def bitSet(off: UInt, dat: Bool): UInt = {
    val bit = UInt(1, 1) << off
    Mux(dat, this | bit, ~(~this | bit))
  }

  def === (that: BitPat): Bool = that === this
  def != (that: BitPat): Bool = that != this

  def zext(): SInt = pushOp(DefPrim(SInt(NO_DIR, width + 1), ConvertOp, ref))
  def asSInt(): SInt = pushOp(DefPrim(SInt(NO_DIR, width), AsSIntOp, ref))
  def asUInt(): UInt = this
}

trait UIntFactory {
  def apply(): UInt = apply(NO_DIR, Width())
  def apply(dir: Direction): UInt = apply(dir, Width())
  def apply(dir: Direction = NO_DIR, width: Int): UInt = apply(dir, Width(width))
  def apply(dir: Direction, width: Width): UInt = new UInt(dir, width)

  def apply(value: BigInt): UInt = apply(value, Width())
  def apply(value: BigInt, width: Int): UInt = apply(value, Width(width))
  def apply(value: BigInt, width: Width): UInt = {
    val lit = ULit(value, width)
    new UInt(NO_DIR, lit.width, Some(lit))
  }
  def apply(n: String, width: Int): UInt = apply(parse(n), width)
  def apply(n: String): UInt = apply(parse(n), parsedWidth(n))

  private def parse(n: String) =
    Literal.stringToVal(n(0), n.substring(1, n.length))
  private def parsedWidth(n: String) =
    if (n(0) == 'b') Width(n.length-1)
    else if (n(0) == 'h') Width((n.length-1) * 4)
    else Width()
}

// Bits constructors are identical to UInt constructors.
object Bits extends UIntFactory
object UInt extends UIntFactory

sealed class SInt(dir: Direction, width: Width, lit: Option[SLit] = None) extends Bits(dir, width, lit) with Num[SInt] {
  override def cloneTypeWidth(w: Width): this.type =
    new SInt(dir, w).asInstanceOf[this.type]
  def toType: Kind = SIntType(width, isFlip)

  override def := (that: Data): Unit = that match {
    case _: SInt => this connect that
    case _ => this badConnect that
  }

  def fromInt(value: BigInt): this.type = SInt(value).asInstanceOf[this.type]
  def makeLit(value: BigInt): SLit = SLit(value, Width())

  def unary_- : SInt = SInt(0) - this
  def unary_-% : SInt = SInt(0) -% this
  def +& (other: SInt): SInt = binop(SInt(NO_DIR, (this.width max other.width) + 1), AddOp, other)
  def + (other: SInt): SInt = this +% other
  def +% (other: SInt): SInt = binop(SInt(NO_DIR, this.width max other.width), AddModOp, other)
  def -& (other: SInt): SInt = binop(SInt(NO_DIR, (this.width max other.width) + 1), SubOp, other)
  def - (other: SInt): SInt = this -% other
  def -% (other: SInt): SInt = binop(SInt(NO_DIR, this.width max other.width), SubModOp, other)
  def * (other: SInt): SInt = binop(SInt(NO_DIR, this.width + other.width), TimesOp, other)
  def * (other: UInt): SInt = binop(SInt(NO_DIR, this.width + other.width), TimesOp, other)
  def / (other: SInt): SInt = binop(SInt(NO_DIR, this.width), DivideOp, other)
  def % (other: SInt): SInt = binop(SInt(NO_DIR, this.width), ModOp, other)

  def & (other: SInt): SInt = binop(SInt(NO_DIR, this.width max other.width), BitAndOp, other)
  def | (other: SInt): SInt = binop(SInt(NO_DIR, this.width max other.width), BitOrOp, other)
  def ^ (other: SInt): SInt = binop(SInt(NO_DIR, this.width max other.width), BitXorOp, other)

  def < (other: SInt): Bool = compop(LessOp, other)
  def > (other: SInt): Bool = compop(GreaterOp, other)
  def <= (other: SInt): Bool = compop(LessEqOp, other)
  def >= (other: SInt): Bool = compop(GreaterEqOp, other)
  def != (other: SInt): Bool = compop(NotEqualOp, other)
  def === (other: SInt): Bool = compop(EqualOp, other)
  def abs(): UInt = Mux(this < SInt(0), (-this).toUInt, this.toUInt)

  def << (other: Int): SInt = binop(SInt(NO_DIR, this.width + other), ShiftLeftOp, other)
  def << (other: BigInt): SInt = this << other.toInt
  def << (other: UInt): SInt = binop(SInt(NO_DIR, this.width.dynamicShiftLeft(other.width)), DynamicShiftLeftOp, other)
  def >> (other: Int): SInt = binop(SInt(NO_DIR, this.width.shiftRight(other)), ShiftRightOp, other)
  def >> (other: BigInt): SInt = this >> other.toInt
  def >> (other: UInt): SInt = binop(SInt(NO_DIR, this.width), DynamicShiftRightOp, other)

  def asUInt(): UInt = pushOp(DefPrim(UInt(NO_DIR, width), AsUIntOp, ref))
  def asSInt(): SInt = this
}

object SInt {
  def apply(): SInt = apply(NO_DIR, Width())
  def apply(dir: Direction): SInt = apply(dir, Width())
  def apply(dir: Direction = NO_DIR, width: Int): SInt = apply(dir, Width(width))
  def apply(dir: Direction, width: Width): SInt = new SInt(dir, width)

  def apply(value: BigInt): SInt = apply(value, Width())
  def apply(value: BigInt, width: Int): SInt = apply(value, Width(width))
  def apply(value: BigInt, width: Width): SInt = {
    val lit = SLit(value, width)
    new SInt(NO_DIR, lit.width, Some(lit))
  }
}

sealed class Bool(dir: Direction, lit: Option[ULit] = None) extends UInt(dir, Width(1), lit) {
  override def cloneTypeWidth(w: Width): this.type = {
    //require(!w.known || w.get == 1)
    new Bool(dir).asInstanceOf[this.type]
  }

  override def fromInt(value: BigInt): this.type = Bool(value).asInstanceOf[this.type]

  def & (other: Bool): Bool = binop(Bool(), BitAndOp, other)
  def | (other: Bool): Bool = binop(Bool(), BitOrOp, other)
  def ^ (other: Bool): Bool = binop(Bool(), BitXorOp, other)

  def || (that: Bool): Bool = this | that
  def && (that: Bool): Bool = this & that

  require(lit.isEmpty || lit.get.num < 2)
}
object Bool {
  def apply(dir: Direction = NO_DIR) : Bool =
    new Bool(dir)
  def apply(value: BigInt) =
    new Bool(NO_DIR, Some(ULit(value, Width(1))))
  def apply(value: Boolean) : Bool = apply(if (value) 1 else 0)
}

object Mux {
  def apply[T <: Data](cond: Bool, con: T, alt: T): T = (con, alt) match {
    // Handle Mux(cond, UInt, Bool) carefully so that the concrete type is UInt
    case (c: Bool, a: Bool) => doMux(cond, c, a).asInstanceOf[T]
    case (c: UInt, a: Bool) => doMux(cond, c, a << 0).asInstanceOf[T]
    case (c: Bool, a: UInt) => doMux(cond, c << 0, a).asInstanceOf[T]
    case (c: Bits, a: Bits) => doMux(cond, c, a).asInstanceOf[T]
    // FIRRTL doesn't support Mux for aggregates, so use a when instead
    case _ => doWhen(cond, con, alt)
  }

  private def doMux[T <: Bits](cond: Bool, con: T, alt: T): T = {
    require(con.getClass == alt.getClass, s"can't Mux between ${con.getClass} and ${alt.getClass}")
    val d = alt.cloneTypeWidth(con.width max alt.width)
    pushOp(DefPrim(d, MultiplexOp, cond.ref, con.ref, alt.ref))
  }
  // This returns an lvalue, which it most definitely should not
  private def doWhen[T <: Data](cond: Bool, con: T, alt: T): T = {
    require(con.getClass == alt.getClass, s"can't Mux between ${con.getClass} and ${alt.getClass}")
    val res = Wire(t = alt.cloneTypeWidth(con.width max alt.width), init = alt)
    when (cond) { res := con }
    res
  }
}

object Cat {
  def apply[T <: Bits](a: T, r: T*): UInt = apply(a :: r.toList)
  def apply[T <: Bits](r: Seq[T]): UInt = {
    if (r.tail.isEmpty) r.head.asUInt
    else {
      val left = apply(r.slice(0, r.length/2))
      val right = apply(r.slice(r.length/2, r.length))
      val w = left.width + right.width

      if (left.isLit && right.isLit)
        UInt((left.litValue() << right.getWidth) | right.litValue(), w)
      else
        pushOp(DefPrim(UInt(NO_DIR, w), ConcatOp, left.ref, right.ref))
    }
  }
}

object Bundle {
  private val keywords =
    HashSet[String]("flip", "asInput", "asOutput", "cloneType", "toBits")
  def apply[T <: Bundle](b: => T)(implicit p: Parameters): T = {
    dynamicContext.paramsScope(p.push){ b }
  }
  //TODO @deprecated("Use Chisel.paramsScope object","08-01-2015")
  def apply[T <: Bundle](b: => T,  f: PartialFunction[Any,Any]): T = {
    val q = dynamicContext.getParams.alterPartial(f)
    apply(b)(q)
  }
}

class Bundle extends Aggregate(NO_DIR) {
  private implicit val _namespace = Builder.globalNamespace.child

  override def <> (that: Data): Unit = that match {
    case _: Bundle => this bulkConnect that
    case _ => this badConnect that
  }

  override def := (that: Data): Unit = this <> that

  def toPorts: Seq[Port] =
    elements.map(_._2.toPort).toSeq.reverse
  def toType: BundleType = 
    BundleType(this.toPorts, isFlip)

  override def flatten: IndexedSeq[UInt] = allElts.flatMap(_._2.flatten)

  lazy val elements: ListMap[String, Data] = ListMap(allElts:_*)

  private def isBundleField(m: java.lang.reflect.Method) =
    m.getParameterTypes.isEmpty && !isStatic(m.getModifiers) &&
    classOf[Data].isAssignableFrom(m.getReturnType) &&
    !(Bundle.keywords contains m.getName)

  private lazy val allElts = {
    val elts = ArrayBuffer[(String, Data)]()
    for (m <- getClass.getMethods; if isBundleField(m)) m.invoke(this) match {
      case data: Data => elts += m.getName -> data
      case _ =>
    }
    elts sortWith {case ((an, a), (bn, b)) => (a._id > b._id) || ((a eq b) && (an > bn))}
  }

  private[Chisel] lazy val namedElts = LinkedHashMap[String, Data](allElts:_*)

  private[Chisel] def addElt(name: String, elt: Data) =
    namedElts += name -> elt

  override def collectElts =
    for ((name, elt) <- namedElts) { elt.setRef(this, name) }

  override def cloneType : this.type = {
    try {
      val constructor = this.getClass.getConstructors.head
      val res = constructor.newInstance(Seq.fill(constructor.getParameterTypes.size)(null):_*)
      res.asInstanceOf[this.type]
    } catch {
      case npe: java.lang.reflect.InvocationTargetException if npe.getCause.isInstanceOf[java.lang.NullPointerException] =>
        ChiselError.error(s"Parameterized Bundle ${this.getClass} needs cloneType method. You are probably using an anonymous Bundle object that captures external state and hence is un-cloneTypeable")
        this
      case npe: java.lang.reflect.InvocationTargetException =>
        ChiselError.error(s"Parameterized Bundle ${this.getClass} needs cloneType method")
        this
    }
  }
}

object Module {
  def apply[T <: Module](bc: => T)(implicit currParams: Parameters = dynamicContext.getParams.push): T = {
    dynamicContext.paramsScope(currParams) {
      val m = dynamicContext.moduleScope{ bc.setRefs() }
      val ports = m.computePorts
      Builder.components += Component(m, m.name, ports, m._commands)
      pushCommand(DefInstance(m, ports))
      m
    }.connectImplicitIOs()
  }
  //TODO @deprecated("Use Chisel.paramsScope object","08-01-2015")
  def apply[T <: Module](m: => T, f: PartialFunction[Any,Any]): T = {
    apply(m)(dynamicContext.getParams.alterPartial(f))
  }
}

abstract class Module(_clock: Clock = null, _reset: Bool = null) extends HasId {
  private implicit val _namespace = Builder.globalNamespace.child
  private[Chisel] val _commands = ArrayBuffer[Command]()
  private[Chisel] val _nodes = ArrayBuffer[Data]()
  private[Chisel] val _children = ArrayBuffer[Module]()
  private[Chisel] val _parent = dynamicContext.getCurrentModule

  dynamicContext.forceCurrentModule(this)
  _parent match {
    case Some(p) => p._children += this
    case _ =>
  }

  val name = Builder.globalNamespace.name(getClass.getName.split('.').last)

  def io: Bundle
  val clock = Clock(INPUT)
  val reset = Bool(INPUT)

  private[Chisel] def ref = Builder.globalRefMap(this)
  private[Chisel] def lref = ref

  def addNode(d: Data) { _nodes += d }

  private def computePorts =
    clock.toPort +: reset.toPort +: io.toPorts

  private def connectImplicitIOs(): this.type = _parent match {
    case Some(p) =>
      clock := (if (_clock eq null) p.clock else _clock)
      reset := (if (_reset eq null) p.reset else _reset)
      this
    case None => this
  }

  private def makeImplicitIOs(): this.type  = {
    io.addElt("clock", clock)
    io.addElt("reset", reset)
    this
  }

  private def setRefs(): this.type = {
    val valNames = HashSet[String](getClass.getDeclaredFields.map(_.getName):_*)
    def isPublicVal(m: java.lang.reflect.Method) =
      m.getParameterTypes.isEmpty && valNames.contains(m.getName)

    makeImplicitIOs
    _nodes.foreach(_.collectElts)

    // FIRRTL: the IO namespace is part of the module namespace
    io.setRef(ModuleIO(this))
    for((name, elt) <- io.namedElts) { _namespace.name(name) }

    val methods = getClass.getMethods.sortWith(_.getName > _.getName)
    for (m <- methods; if isPublicVal(m)) m.invoke(this) match {
      case id: HasId => id.setRef(m.getName)
      case _ =>
    }
    (_nodes ++ _children).foreach(_.setRef)
    this
  }

  // TODO: actually implement these
  def assert(cond: Bool, msg: String): Unit = {}
  def printf(message: String, args: Bits*): Unit = {}
}

// TODO: actually implement BlackBox (this hack just allows them to compile)
abstract class BlackBox(_clock: Clock = null, _reset: Bool = null) extends Module(_clock = _clock, _reset = _reset) {
  def setVerilogParameters(s: String): Unit = {}
}

object when {
  def apply(cond: => Bool)(block: => Unit): WhenContext = {
    new WhenContext(cond)(block)
  }
}

class WhenContext(cond: => Bool)(block: => Unit) {
  def elsewhen (cond: => Bool)(block: => Unit): WhenContext =
    doOtherwise(when(cond)(block))

  def otherwise(block: => Unit): Unit =
    doOtherwise(block)

  pushCommand(WhenBegin(cond.ref))
  block
  pushCommand(WhenEnd())

  private def doOtherwise[T](block: => T): T = {
    pushCommand(WhenElse())
    val res = block
    pushCommand(WhenEnd())
    res
  }
}
