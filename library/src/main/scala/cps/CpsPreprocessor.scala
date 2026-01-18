package cps

import language.experimental.captureChecking

/**
 * Typeclass for preprocessing async block bodies before CPS transformation.
 *
 * CpsPreprocessor allows monads to customize how code inside async blocks
 * is transformed at compile time, before the main CPS transformation occurs.
 *
 * Use cases include:
 * - Durable monad: Wrap val definitions with caching for replay-based recovery
 * - Tracing monad: Insert logging/tracing calls around expressions
 * - STM monad: Add savepoint markers for transactional memory
 * - Profiling: Wrap expressions with timing measurements
 *
 * The preprocessor is applied in Stage 1 of async block processing,
 * before Stage 2 (CPS transformation by plugin) sees the code.
 * This allows preprocessors to insert await calls that the plugin
 * will then properly transform.
 *
 * @tparam F the monad type constructor
 * @tparam C the context type (subtype of CpsMonadContext[F])
 *
 * Example:
 * {{{
 *   given CpsPreprocessor[DurableMonad, DurableContext] with
 *     transparent inline def preprocess[A](inline body: A, inline ctx: DurableContext): A =
 *       ${ DurablePreprocessorMacro.impl[A]('body, 'ctx) }
 * }}}
 */
trait CpsPreprocessor[F[_], C <: CpsMonadContext[F]]:

  /**
   * Preprocess the async block body before CPS transformation.
   *
   * This method must be transparent inline to ensure the preprocessing
   * macro expands before Stage 2 sees the code.
   *
   * @tparam A the result type of the body
   * @param body the async block body to preprocess
   * @param ctx the monad context (passed explicitly for macro access)
   * @return the preprocessed body (same type, possibly transformed AST)
   */
  transparent inline def preprocess[A](inline body: A, inline ctx: C): A

end CpsPreprocessor
