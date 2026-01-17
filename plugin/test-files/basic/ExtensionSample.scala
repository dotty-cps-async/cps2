package testfiles.basic

import language.experimental.captureChecking
import cps.*

object ExtensionSample:

  // Extension method with impure function parameter - should trigger generation
  extension [A](list: List[A])
    def myMap[B](f: A => B): List[B] =
      list.map(f)

  // Extension method with pure function parameter - should NOT trigger generation
  extension [A](list: List[A])
    def myPureMap[B](f: A -> B): List[B] =
      list.map(f)

  // Extension method with context function - should trigger generation
  extension [A](opt: Option[A])
    def myFlatMap[B](f: A ?=> Option[B]): Option[B] =
      opt.flatMap(x => f(using x))

  // Regular extension method (no function params) - should NOT trigger generation
  extension (x: Int)
    def double: Int = x * 2
