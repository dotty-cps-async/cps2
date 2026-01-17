package cps.plugin.cps2.forest

import dotty.tools.dotc.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.*

import cps.plugin.cps2.*

/**
 * Transform lambda expressions.
 * Lambda is represented as Block((ddef: DefDef) :: Nil, closure: Closure)
 */
object LambdaTransform {

  def apply(lambda: Block, ddef: DefDef, closure: Closure, owner: Symbol)(
    using Context, CpsTransformContext
  ): CpsTree = {
    // Get body with correct context
    val ddefCtx = summon[Context].withOwner(ddef.symbol)
    val ddefRhs = ddef.rhs(using ddefCtx)
    // Transform body
    val bodyCps = RootTransform(ddefRhs, ddef.symbol)(using ddefCtx, summon[CpsTransformContext])
    LambdaCpsTree(lambda, owner, ddef, bodyCps)
  }
}
