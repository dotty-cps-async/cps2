package cps.plugin.cps2.forest

import dotty.tools.dotc.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.*

import cps.plugin.cps2.*

/**
 * Transform Block trees.
 *
 * Three cases:
 * 1. Lambda: Block((ddef: DefDef) :: Nil, closure: Closure) where ddef.symbol == closure.meth.symbol
 * 2. Empty block: Block(Nil, expr)
 * 3. Regular block: Block(stats, expr)
 *
 * BlockBoundsCpsTree is used to preserve lexical scoping - prevents ValDefs from
 * inner blocks leaking into outer scope when blocks are flattened by appendInBlock.
 */
object BlockTransform {

  def apply(block: Block, owner: Symbol)(using Context, CpsTransformContext): CpsTree = {
    block match {
      // Lambda
      case Block((ddef: DefDef) :: Nil, closure: Closure) if ddef.symbol == closure.meth.symbol =>
        LambdaTransform(block, ddef, closure, owner)

      // Empty block
      case Block(Nil, expr) =>
        val exprCps = RootTransform(expr, owner)
        BlockBoundsCpsTree(exprCps)

      // Regular block with statements
      case Block(stats, expr) =>
        val s0: CpsTree = CpsTree.unit(owner)
        val statsCps = stats.foldLeft(s0) { (s, e) =>
          val eCps = RootTransform(e, owner)
          s.appendInBlock(eCps)
        }
        val exprCps = RootTransform(expr, owner)
        val blockCps = statsCps.appendInBlock(exprCps).withOrigin(block)
        BlockBoundsCpsTree(blockCps)
    }
  }
}
