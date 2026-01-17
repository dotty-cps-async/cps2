package testfiles.crossref

import language.experimental.captureChecking

/**
 * Usage file that has HOFs returning cross-referenced function-storing classes.
 * Tests that delayed classes from different files are properly resolved.
 */
object CrossRefUsage:

  // HOF that returns LazyA (function-storing class from LazyA.scala)
  // Should generate: lazyMapA_async returning LazyA_cps
  def lazyMapA[A, B](list: List[A])(f: A => B): LazyA[A, B]^{f} =
    LazyA(list, f)

  // HOF that returns LazyB (function-storing class from LazyB.scala)
  // Should generate: lazyMapB_async returning LazyB_cps
  def lazyMapB[A, B](list: List[A])(f: A => B): LazyB[A, B]^{f} =
    LazyB(list, f)

  // HOF that chains both
  def chainAB[A, B, C](list: List[A])(f: A => B)(g: B => C): LazyB[A, C]^{f, g} =
    LazyA(list, f).chain(g)
