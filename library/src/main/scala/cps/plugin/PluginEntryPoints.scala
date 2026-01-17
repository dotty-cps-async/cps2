package cps.plugin

import cps.*
import scala.annotation.compileTimeOnly

/**
 * Phase 1 scaffolding for CPS-transformed method bodies.
 *
 * Used for:
 * - Shifted HOF methods (async variants like map_async)
 * - Direct functions (functions with CpsMonadContext capability)
 *
 * Generated in Phase 1 with original body (with await calls).
 * Transformed in Phase 2 to actual CPS-transformed code.
 *
 * @tparam F monad type constructor
 * @tparam T original result type
 * @tparam R cps-transformed result type (= cpsTransformedType(T, F))
 * @param m monad instance
 * @param origin original body with await calls
 * @return R (replaced by CPS-transformed body in Phase 2)
 */
// TODO: restore @compileTimeOnly when Phase 2 is implemented
// @compileTimeOnly("cpsPhase1Scaffold should be transformed by cps2 plugin")
def cpsPhase1Scaffold[F[_], T, R](m: CpsMonad[F], origin: T): R =
  throw new RuntimeException("cpsPhase1Scaffold should be transformed by plugin")

/**
 * Await placeholder used inside cpsPhase1Scaffold body.
 * Will be transformed to proper await in Phase 2.
 */
// TODO: restore @compileTimeOnly when Phase 2 is implemented
// @compileTimeOnly("scaffoldAwait should be inside cpsPhase1Scaffold")
def scaffoldAwait[F[_], T](fa: F[T]): T =
  throw new RuntimeException("scaffoldAwait should be transformed by plugin")
