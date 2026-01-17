package testfiles.ctor

import language.experimental.captureChecking

case class Foo[A, B](x: A, y: B)

object Test:
  def test(): Foo[Int, String] = 
    new Foo[Int, String](1, "hello")
