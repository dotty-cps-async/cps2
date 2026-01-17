package cps.monads

import cps.*
import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Try, Success, Failure}
import language.experimental.captureChecking

class FutureCpsMonadContext(using val ec: ExecutionContext) extends CpsTryMonadContext[Future]:
  override def monad: CpsTryMonad[Future] = FutureCpsMonad(using ec)

given futureCpsMonad(using ec: ExecutionContext): CpsTryMonad[Future] with

  override type Context = FutureCpsMonadContext

  override def pure[A](value: A): Future[A] =
    Future.successful(value)

  override def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B] =
    fa.flatMap(f)(using ec)

  override def map[A, B](fa: Future[A])(f: A => B): Future[B] =
    fa.map(f)(using ec)

  override def error[A](e: Throwable): Future[A] =
    Future.failed(e)

  override def flatMapTry[A, B](fa: Future[A])(f: Try[A] => Future[B]): Future[B] =
    fa.transformWith(f)(using ec)

  override def mapTry[A, B](fa: Future[A])(f: Try[A] => B): Future[B] =
    fa.transform(f)(using ec)

  override def apply[T](op: Context ?=> T): Future[T] =
    val ctx = FutureCpsMonadContext(using ec)
    Future(op(using ctx))(using ec)

end futureCpsMonad

object FutureCpsMonad:
  def apply(using ec: ExecutionContext): CpsTryMonad[Future] = futureCpsMonad(using ec)
