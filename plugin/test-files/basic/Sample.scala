package testfiles.basic

import language.experimental.captureChecking
import cps.*

object Sample:
  def simpleMethod(x: Int): Int = x + 1

  def asyncMethod(x: Int): Int =
    x * 2

  def transformMethod(a: String, b: Int): String =
    s"$a-$b"

  // Pure function - won't trigger shifted method generation when CC is enabled
  def higherOrderMethodPure(x: Int, f: Int -> Int): Int =
    f(x) + 1

  // Impure function - WILL trigger shifted method generation
  def higherOrderMethod(x: Int, f: Int => Int): Int =
    f(x) + 1

  // Context function - always impure
  def contextHigherOrder[T](x: T)(f: T ?=> Int): Int =
    f(using x)