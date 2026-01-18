package cps

import language.experimental.captureChecking

/**
 * CPS capability marker.
 */
trait CpsCapability extends caps.Control

trait CpsMonadContext[F[_]] extends CpsCapability {

  def monad: CpsMonad[F]

}

/**
 * Monad type for CPS computations
 */
trait CpsMonad[F[_]]:

  type Context <: CpsMonadContext[F]

  def pure[A](value: A): F[A]

  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]

  def map[A, B](fa: F[A])(f: A => B): F[B] =
    flatMap(fa)(a => pure(f(a)))

  /** Create a fresh context for this monad */
  def createContext(): Context

  /** Apply operation with given context - capability flows through parameter */
  def applyWithContext[T](ctx: Context)(op: Context => T): F[T]

  /** Apply operation - creates context and delegates to applyWithContext */
  def apply[T](op: Context ?=> T): F[T] =
    applyWithContext(createContext())(ctx => op(using ctx))

end CpsMonad

object CpsMonad:
  /** Auxiliary type to expose Context as a type parameter instead of path-dependent type */
  type Aux[F[_], C <: CpsMonadContext[F]] = CpsMonad[F] { type Context = C }
end CpsMonad


trait CpsThrowMonad[F[_]] extends CpsMonad[F]:

  override type Context <: CpsThrowMonadContext[F]

  def error[A](e: Throwable): F[A]

end CpsThrowMonad

trait CpsThrowMonadContext[F[_]] extends CpsMonadContext[F]:

  override def monad: CpsThrowMonad[F]

end CpsThrowMonadContext


trait CpsTryMonad[F[_]] extends CpsThrowMonad[F]:

  override type Context <: CpsTryMonadContext[F]

  def flatMapTry[A, B](fa: F[A])(f: scala.util.Try[A] => F[B]): F[B]

  def mapTry[A, B](fa: F[A])(f: scala.util.Try[A] => B): F[B] =
    flatMapTry(fa)(ta => pure(f(ta)))

  def restore[A](fa: F[A])(fx: Throwable => F[A]): F[A] =
    flatMapTry(fa) {
      case scala.util.Success(a) => pure(a)
      case scala.util.Failure(ex) => fx(ex)
    }

end CpsTryMonad

trait CpsTryMonadContext[F[_]] extends CpsThrowMonadContext[F]:

  override def monad: CpsTryMonad[F]

end CpsTryMonadContext
