package cps.plugin.cps2.forest

import dotty.tools.dotc.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.*

import cps.plugin.cps2.*

/**
 * Root transform - entry point for CPS transformation of any tree.
 */
object RootTransform {

  def apply(tree: Tree, owner: Symbol)(using Context, CpsTransformContext): CpsTree = {
    tree match {
      case lit: Literal =>
        CpsTree.unchangedPure(lit, owner)

      case id: Ident =>
        CpsTree.unchangedPure(id, owner)

      case th: This =>
        CpsTree.unchangedPure(th, owner)

      case block: Block =>
        BlockTransform(block, owner)

      case other =>
        throw CpsTransformException(
          s"CPS transform not yet implemented for: ${other.getClass.getSimpleName}",
          other.srcPos
        )
    }
  }
}
