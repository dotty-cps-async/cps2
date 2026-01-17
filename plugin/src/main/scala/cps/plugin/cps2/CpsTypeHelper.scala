package cps.plugin.cps2

import dotty.tools.dotc.*
import dotty.tools.dotc.config.Feature
import core.*
import core.Contexts.*
import core.Types.*
import core.Flags.*
import core.NameOps.*

/**
 * Shared type utilities for CPS transformation.
 */
object CpsTypeHelper {

  /**
   * Check if a type is any kind of function (pure or impure).
   */
  def isAnyFunction(tp: Type)(using ctx: Context): Boolean = {
    import ctx.definitions as defn
    tp.widen.dealias match
      case _: AppliedType if defn.isFunctionType(tp) || defn.isContextFunctionType(tp) => true
      case _: MethodType => true
      case _: PolyType => true
      case _ => false
  }

  /**
   * Check if a type is an impure function (can capture capabilities).
   *
   * With capture checking enabled:
   * - Pure functions (A -> B) cannot capture and don't need CPS transformation
   * - Impure functions (A => B) can capture and need CPS transformation
   *
   * Without capture checking:
   * - All functions are treated as potentially impure
   */
  def isImpureFunction(tp: Type)(using ctx: Context): Boolean = {
    import ctx.definitions as defn
    if (Feature.ccEnabled) {
      // Capture checking is enabled - only impure functions need shifting
      // Check for ImpureFunction BEFORE widen.dealias because dealiasing
      // strips the ImpureFunction1 alias back to Function1
      val tpSym = tp.typeSymbol
      if (tpSym.name.isImpureFunction) {
        return true
      }

      tp.widen.dealias match
        case AppliedType(tycon, _) =>
          val sym = tycon.typeSymbol
          // Context functions are always impure (can capture)
          defn.isContextFunctionType(tp)
        case _: MethodType => true  // Method types are inherently impure
        case _: PolyType => true    // Poly types wrapping methods
        case _ => false
    } else {
      // Capture checking is NOT enabled - treat all functions as potentially impure
      isAnyFunction(tp)
    }
  }

}
