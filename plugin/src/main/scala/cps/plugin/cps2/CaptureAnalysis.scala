package cps.plugin.cps2

import dotty.tools.dotc.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.config.Feature

/**
 * Capture set analysis utilities for CPS transformation.
 *
 * With Scala 3.8 capture checking, CpsMonadContext[F] serves as both:
 * 1. The monad context (provides monad operations)
 * 2. The capability instance (tracked by capture checking)
 *
 * A **direct function** is any function whose result type's capture set
 * contains CpsMonadContext. The capability can come from any parameter
 * (using, regular, or context function) - what matters is that the
 * result type captures it.
 */
object CaptureAnalysis {

  /**
   * Check if a type's capture set contains CpsMonadContext.
   *
   * This is the fundamental detection mechanism for async lambdas
   * and direct functions.
   */
  def hasCpsCapability(tpe: Type)(using Context): Boolean = {
    if (!Feature.ccEnabled) {
      // Capture checking not enabled - cannot detect via capture sets
      return false
    }

    // Import capture checking APIs
    import dotty.tools.dotc.cc.{CapturingType, CaptureSet}
    import dotty.tools.dotc.cc.Capabilities.CoreCapability
    import dotty.tools.dotc.cc.*  // for extension methods

    val cpsMonadContextClass = Symbols.getClassIfDefined("cps.CpsMonadContext")
    if (!cpsMonadContextClass.exists) {
      return false
    }

    def checkCapability(cap: dotty.tools.dotc.cc.Capabilities.Capability): Boolean = {
      cap match {
        case core: CoreCapability =>
          // CoreCapability extends TypeProxy, so we can use type methods
          core.widen.typeSymbol.derivesFrom(cpsMonadContextClass)
        case _ =>
          false
      }
    }

    // Check capture set of the type
    tpe match {
      case CapturingType(parent, refs) =>
        refs.elems.exists(checkCapability)
      case _ =>
        // Also check via captureSet extension method
        val cs = tpe.captureSet
        cs.elems.exists(checkCapability)
    }
  }

  /**
   * Check if a symbol represents a direct function.
   *
   * A direct function is one whose result type's capture set
   * contains CpsMonadContext.
   */
  def isDirectFunction(sym: Symbol)(using Context): Boolean = {
    if (!sym.isRealMethod) return false

    val resultType = sym.info.finalResultType
    hasCpsCapability(resultType)
  }

  /**
   * Check if a DefDef is a direct function.
   */
  def isDirectFunction(tree: tpd.DefDef)(using Context): Boolean = {
    isDirectFunction(tree.symbol)
  }

  /**
   * Extract the CpsMonadContext type parameter F from a capture set.
   *
   * For a function with result type T^{ctx} where ctx: CpsMonadContext[F],
   * this extracts F.
   */
  def extractMonadType(tpe: Type)(using Context): Option[Type] = {
    if (!Feature.ccEnabled) return None

    import dotty.tools.dotc.cc.{CapturingType, CaptureSet}
    import dotty.tools.dotc.cc.Capabilities.CoreCapability
    import dotty.tools.dotc.cc.*  // for extension methods

    val cpsMonadContextClass = Symbols.getClassIfDefined("cps.CpsMonadContext")
    if (!cpsMonadContextClass.exists) return None

    val cs = tpe.captureSet
    // Iterate through elements to find CpsMonadContext
    cs.elems.toList.collectFirst {
      case core: CoreCapability if core.widen.typeSymbol.derivesFrom(cpsMonadContextClass) =>
        core.widen match {
          case AppliedType(_, args) if args.nonEmpty => Some(args.head)
          case _ => None
        }
    }.flatten
  }

  /**
   * Get detailed info about a direct function for debugging.
   */
  def directFunctionInfo(sym: Symbol)(using Context): Option[DirectFunctionInfo] = {
    if (!isDirectFunction(sym)) return None

    val resultType = sym.info.finalResultType
    val monadType = extractMonadType(resultType)

    Some(DirectFunctionInfo(
      symbol = sym,
      resultType = resultType,
      monadType = monadType
    ))
  }

  /**
   * Information about a detected direct function.
   */
  case class DirectFunctionInfo(
    symbol: Symbol,
    resultType: Type,
    monadType: Option[Type]
  )

}
