package testfiles.basic

import language.experimental.captureChecking
import cps.*
import cps.annotation.*

/**
 * Test file to verify:
 * 1. Plugin can create a class symbol
 * 2. Plugin can add @delayedVariant annotation referencing that symbol
 * 3. The annotation is properly accessible
 *
 * Expected behavior:
 * - Plugin detects FunctionHolder stores functions
 * - Plugin generates FunctionHolder_cps class in companion
 * - Plugin adds @delayedVariant[FunctionHolder_cps] to FunctionHolder
 */
object DelayedAnnotationTest:

  // This class stores a function - should trigger delayed class generation
  // Plugin should ADD: @delayedVariant[FunctionHolder_cps]
  class FunctionHolder[A, B](val transform: A => B):
    def apply(a: A): B = transform(a)

  // Test with user-provided delayed variant
  @delayedVariant[CustomDelayed]
  class WithCustomDelayed[A](val filter: A => Boolean)

  // User-provided delayed class
  class CustomDelayed[F[_], A](val filter: A => F[Boolean])

  // Method returning FunctionHolder - should use delayed type
  def createHolder[A, B](f: A => B): FunctionHolder[A, B]^{f} =
    FunctionHolder(f)
