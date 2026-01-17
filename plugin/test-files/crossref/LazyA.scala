package testfiles.crossref

import language.experimental.captureChecking

/**
 * Function-storing class A that references class B.
 * Plugin should generate LazyA_cps[F_SHIFT, A, B]
 */
class LazyA[A, B](source: List[A], val f: A => B):
  def toList: List[B] = source.map(f)

  // Method that returns another function-storing class
  def chain[C](g: B => C): LazyB[A, C]^{f, g} =
    LazyB(source, x => g(f(x)))
