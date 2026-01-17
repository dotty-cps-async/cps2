package direct

import language.experimental.captureChecking
import cps.*

/**
 * Test file for direct function detection.
 *
 * A direct function is one whose result type captures CpsMonadContext.
 * The plugin should detect these and mark them with @directFunction.
 */
object DirectFunctionSample {

  // Direct function with using parameter
  // Result type captures ctx: String^{ctx}
  def fetchWithUsing(url: String)(using ctx: CpsMonadContext[List]): String =
    "result"

  // Direct function with regular parameter
  // Result type captures ctx: String^{ctx}
  def fetchWithParam(url: String, ctx: CpsMonadContext[List]): String =
    "result"

  // NOT a direct function - no CpsMonadContext in scope
  def regularFunction(x: Int): String =
    x.toString

  // Direct function with type parameter
  def fetchGeneric[F[_]](url: String)(using ctx: CpsMonadContext[F]): String =
    "result"

  // Higher-order direct function
  def processWithCallback[F[_]](
    data: String,
    callback: String => String
  )(using ctx: CpsMonadContext[F]): String =
    callback(data)

}

// Extension methods can also be direct functions
extension (s: String)
  // Direct extension method - CpsMonadContext in method signature
  def fetchExtension[F[_]](using ctx: CpsMonadContext[F]): String =
    s + " fetched"

  // NOT a direct extension method - no CpsMonadContext
  def toUpperDirect: String =
    s.toUpperCase

// CpsMonadContext in extension head
extension [F[_]](s: String)(using ctx: CpsMonadContext[F])
  // Direct extension - ctx comes from extension head
  def fetchFromHead: String =
    s + " from head"

  // Another direct extension using head's ctx
  def processFromHead(suffix: String): String =
    s + suffix
