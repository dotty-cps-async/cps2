package testfiles.context

import language.experimental.captureChecking
import cps.*

// More specific context type with additional capabilities
trait CpsLogicMonadContext[F[_]] extends CpsMonadContext[F]:
  def mzero[A]: F[A]

// Monad that uses the specific context
trait CpsLogicMonad[F[_]] extends CpsMonad[F]:
  override type Context <: CpsLogicMonadContext[F]

object CpsLogicMonad:
  type Aux[F[_], C <: CpsLogicMonadContext[F]] = CpsLogicMonad[F] { type Context = C }

// Function that requires specific context type
def guard[F[_]](condition: Boolean)(using ctx: CpsLogicMonadContext[F]): Unit =
  if !condition then
    // Would normally do: await(ctx.mzero[Unit])
    // For now just check that ctx.mzero is accessible
    val _ = ctx.mzero[Unit]
    ()

// Simple test monad implementation
class ListMonadContext extends CpsLogicMonadContext[List]:
  def monad: CpsMonad[List] = ListMonad
  def mzero[A]: List[A] = Nil

object ListMonad extends CpsLogicMonad[List]:
  type Context = ListMonadContext

  def pure[A](a: A): List[A] = List(a)
  def flatMap[A, B](fa: List[A])(f: A => List[B]): List[B] = fa.flatMap(f)

  def createContext(): Context = new ListMonadContext

  def applyWithContext[T](ctx: Context)(op: Context ?=> T): List[T] =
    List(op(using ctx))

  def apply[T](op: Context ?=> T): List[T] =
    applyWithContext(createContext())(op)

// Provide given instance
given listMonad: CpsLogicMonad[List] = ListMonad

// Test: Can we use guard inside async block?
object SpecificContextUsage:

  def testGuardInAsync(): List[Int] =
    async[List] {
      guard[List](true)  // Does this compile? Does it find CpsLogicMonadContext[List]?
      42
    }

  def testGuardWithCondition(x: Int): List[Int] =
    async[List] {
      guard[List](x > 0)
      x * 2
    }
