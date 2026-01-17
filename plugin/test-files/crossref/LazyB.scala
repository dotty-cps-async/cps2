package testfiles.crossref

import language.experimental.captureChecking

/**
 * Function-storing class B that references class A.
 * Plugin should generate LazyB_cps[F_SHIFT, A, B]
 */
class LazyB[A, B](source: List[A], val f: A => B):
  def toList: List[B] = source.map(f)

  // Method that returns class A (cross-reference)
  def reverse[C](g: B => C): LazyA[A, C]^{f, g} =
    LazyA(source, x => g(f(x)))
