package cps

import scala.annotation.compileTimeOnly
import scala.annotation.retainsCap
import scala.compiletime.summonFrom
import language.experimental.captureChecking

extension [F[_], T, G[_]](f: F[T])(using ctx: CpsMonadContext[G], conversion: CpsMonadConversion[F, G])
  @compileTimeOnly("await should be inside async block")
  def await: T = ???

extension [F[_], T, G[_]](f: F[T])(using ctx: CpsMonadContext[G], conversion: CpsMonadConversion[F, G])
  @compileTimeOnly("reflect should be inside async block")
  def reflect: T = ???

/**
 * Creates an async block for CPS transformation.
 * Usage: async[F] { body } where F is the monad type.
 */
inline def async[F[_]]: AsyncApply[F] = new AsyncApply[F]

/**
 * Synonym for async.
 */
inline def reify[F[_]]: AsyncApply[F] = new AsyncApply[F]

/**
 * Helper class to enable syntax: async[F] { body }
 * C is inferred from monad to preserve specific context types (e.g., CpsLogicMonadContext).
 * Supports CpsPreprocessor for monad-specific AST transformations.
 */
class AsyncApply[F[_]]:
  transparent inline def apply[C <: CpsMonadContext[F], T](using monad: CpsMonad.Aux[F, C])(inline body: C ?=> T): F[T] =
    summonFrom {
      case pp: CpsPreprocessor[F, C] =>
        monad.apply((ctx: C) ?=> pp.preprocess[T](body(using ctx), ctx))
      case _ =>
        monad.apply(body)
    }
