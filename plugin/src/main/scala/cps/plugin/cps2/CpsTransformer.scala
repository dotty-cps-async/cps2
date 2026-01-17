package cps.plugin.cps2

import dotty.tools.dotc.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.*

import cps.plugin.cps2.forest.RootTransform

/**
 * CPS Transformer - entry point for CPS transformation.
 * Delegates to RootTransform in forest package.
 */
object CpsTransformer {

  def transform(tree: Tree, owner: Symbol)(using Context, CpsTransformContext): CpsTree =
    RootTransform(tree, owner)
}
