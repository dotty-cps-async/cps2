package testfiles.basic

import language.experimental.captureChecking
import cps.*

/**
 * Test file for call-chain substitution detection.
 * Methods that STORE function arguments need special handling.
 *
 * With capture checking, classes that store functions must capture
 * what those functions capture. This is exactly what we detect!
 */
object CallChainSample:

  // A simple "lazy wrapper" that stores a function
  // Note: The class captures the function's capabilities
  // The plugin should AUTO-GENERATE LazyTransform_cps for this class
  class LazyTransform[A, B](source: List[A], val f: A => B):
    def toList: List[B] = source.map(f)
    // For now, simplified version without chaining to avoid CC complexity
    // def map[C](g: B => C): LazyTransform[A, C] = LazyTransform(source, f.andThen(g))

  // Standard HOF - function is applied immediately
  // Should generate: myMap_async[F[_], A, B](m)(f: A => F[B]): F[List[B]]
  def myMap[A, B](list: List[A])(f: A => B): List[B] =
    list.map(f)

  // Call-chain method - function is STORED in LazyTransform
  // With CC, the return type captures what f captures
  // Should detect as CallChain and find DelayedLazyTransform
  def myLazyMap[A, B](list: List[A])(f: A => B): LazyTransform[A, B]^{f} =
    LazyTransform(list, f)

  // Another example with context function
  class LazyFilter[A](source: List[A], val p: A => Boolean):
    def toList: List[A] = source.filter(p)

  // Call-chain with context function - no delayed type provided, will fallback to F[T]
  def myLazyFilter[A](list: List[A])(p: A ?=> Boolean): LazyFilter[A]^{p} =
    LazyFilter(list, x => p(using x))

  // Extension method that's a call-chain
  extension [A](list: List[A])
    def lazyWhere(p: A => Boolean): LazyFilter[A]^{p} =
      LazyFilter(list, p)
