// See LICENSE for license details.

package chiselTests

import chisel3._
import scala.language.experimental.macros
import org.scalatest._
import org.scalatest.prop._
import chisel3.testers.BasicTester

package object rangeMacroTest {

implicit class ChiselRange(val sc: StringContext) extends AnyVal {
  def range(args: Any*): Unit = macro chisel3.internal.RangeTransform.apply
}

}

import rangeMacroTest._

/** Comprehensive test of static range parsing functionality.
  * Note: negative (failure) conditions can't be tested because they will fail at compile time,
  * before the testing environment is entered.
  */
@dump
class RangeMacroTest extends ChiselPropSpec {
  property("Range macros should work") {
    def ducks() = {2}
    range"  (0, ${ducks}]   "
  }
}
