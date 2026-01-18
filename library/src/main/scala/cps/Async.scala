package cps

import scala.annotation.compileTimeOnly
import scala.annotation.retainsCap
import scala.annotation.unchecked.uncheckedCaptures
import scala.quoted.*
import language.experimental.captureChecking

extension [F[_], T, G[_]](f: F[T])(using ctx: CpsMonadContext[G], conversion: CpsMonadConversion[F, G])
  @compileTimeOnly("await should be inside async block")
  def await: T = ???

extension [F[_], T, G[_]](f: F[T])(using ctx: CpsMonadContext[G], conversion: CpsMonadConversion[F, G])
  @compileTimeOnly("reflect should be inside async block")
  def reflect: T = ???

/**
 * Creates an async block for CPS transformation.
 * Usage: async[F] { body } where F is the monad type.
 */
inline def async[F[_]]: AsyncApply[F] = new AsyncApply[F]

/**
 * Synonym for async.
 */
inline def reify[F[_]]: AsyncApply[F] = new AsyncApply[F]

/**
 * Helper class to enable syntax: async[F] { body }
 * C is inferred from monad to preserve specific context types (e.g., CpsLogicMonadContext).
 * Supports CpsPreprocessor for monad-specific AST transformations.
 */
class AsyncApply[F[_]]:
  transparent inline def apply[C <: CpsMonadContext[F], T](using monad: CpsMonad.Aux[F, C])(inline body: C ?=> T): F[T] =
    ${ AsyncApply.applyImpl[F, T]('monad, 'body) }

object AsyncApply:
  // Note: We don't take C as a type parameter to avoid capture checking issues.
  // Instead, we extract the context type from the monad at the tree level.
  def applyImpl[F[_]: Type, T: Type](
      monad: Expr[CpsMonad[F]],
      body: Expr[? ?=> T]  // Context function with unknown context type
  )(using Quotes): Expr[F[T]] =
    import quotes.reflect.*

    // Extract the Context type from the monad's type at tree level
    val monadType = monad.asTerm.tpe.widen
    val contextType = monadType.select(monadType.typeSymbol.typeMember("Context"))

    // Build the preprocessor type entirely at tree level to avoid capture checking issues
    // Look up CpsPreprocessor symbol directly
    val cpsPreprocessorSymbol = Symbol.requiredClass("cps.CpsPreprocessor")
    val preprocessorType = cpsPreprocessorSymbol.typeRef.appliedTo(List(TypeRepr.of[F], contextType))

    Implicits.search(preprocessorType) match
      case iss: ImplicitSearchSuccess =>
        // Use the concrete type from the search result
        val ppTree = iss.tree
        val ppConcreteType = ppTree.tpe.widen
        report.info(s"Found preprocessor of type: ${ppConcreteType.show}")

        // Find the preprocess method in the concrete type
        val preprocessMethod = ppConcreteType.typeSymbol.methodMember("preprocess").head

        // Build the lambda: c => pp.preprocess[T](body(using c), c)
        val lambdaType = MethodType(List("c"))(
          _ => List(contextType),
          _ => TypeRepr.of[T]
        )

        val lambda = Lambda(
          Symbol.spliceOwner,
          lambdaType,
          (owner, params) => {
            val cRef = params.head.asInstanceOf[Term]

            // body(using c)
            val bodyApplied = Apply(body.asTerm, List(cRef))

            // pp.preprocess[T](bodyApplied, c)
            Apply(
              Apply(
                TypeApply(
                  Select(ppTree, preprocessMethod),
                  List(TypeTree.of[T])
                ),
                List(bodyApplied)
              ),
              List(cRef)
            )
          }
        )

        // Build: val ctx = monad.createContext(); monad.applyWithContext(ctx)(lambda)
        val createContextMethod = monadType.typeSymbol.methodMember("createContext").head
        val applyWithContextMethod = monadType.typeSymbol.methodMember("applyWithContext").head

        // Build the entire expression at tree level to avoid quote/capture issues
        Block(
          List(
            ValDef(
              Symbol.newVal(Symbol.spliceOwner, "ctx", contextType, Flags.EmptyFlags, Symbol.noSymbol),
              Some(Apply(Select(monad.asTerm, createContextMethod), Nil))
            )
          ),
          Apply(
            Apply(
              Select(monad.asTerm, applyWithContextMethod),
              List(Ref(Symbol.spliceOwner.declarations.find(_.name == "ctx").get))
            ),
            List(lambda)
          )
        ).asExprOf[F[T]]

      case isf: ImplicitSearchFailure =>
        // No preprocessor found, generate simple code
        val createContextMethod = monadType.typeSymbol.methodMember("createContext").head
        val applyWithContextMethod = monadType.typeSymbol.methodMember("applyWithContext").head

        val lambdaType = MethodType(List("c"))(
          _ => List(contextType),
          _ => TypeRepr.of[T]
        )

        val lambda = Lambda(
          Symbol.spliceOwner,
          lambdaType,
          (owner, params) => {
            val cRef = params.head.asInstanceOf[Term]
            Apply(body.asTerm, List(cRef))
          }
        )

        Block(
          List(
            ValDef(
              Symbol.newVal(Symbol.spliceOwner, "ctx", contextType, Flags.EmptyFlags, Symbol.noSymbol),
              Some(Apply(Select(monad.asTerm, createContextMethod), Nil))
            )
          ),
          Apply(
            Apply(
              Select(monad.asTerm, applyWithContextMethod),
              List(Ref(Symbol.spliceOwner.declarations.find(_.name == "ctx").get))
            ),
            List(lambda)
          )
        ).asExprOf[F[T]]
