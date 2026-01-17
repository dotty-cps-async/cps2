package cps.plugin.cps2

import dotty.tools.dotc.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Constants.*
import dotty.tools.dotc.report

/**
 * Context for CPS transformation.
 *
 * Holds the monad type F[_], monad reference, and other transformation context.
 */
case class CpsTransformContext(
  /** The monad type constructor F[_] */
  monadType: Type,
  /** Reference to the monad instance (for calling pure, map, flatMap) */
  monadRef: Tree,
  /** The owner symbol for generated code */
  owner: Symbol
)

/**
 * Async kind - tracks whether an expression is sync, async, or an async lambda.
 */
enum AsyncKind:
  case Sync
  case Async(internal: AsyncKind)
  case AsyncLambda(body: AsyncKind)

/**
 * CpsTree - intermediate representation for CPS-transformed expressions.
 *
 * Hierarchy:
 *   CpsTree
 *     |- SyncCpsTree (unpure returns Some)
 *     |    |- PureCpsTree
 *     |    |- UnitCpsTree
 *     |- AsyncCpsTree (unpure returns None)
 *     |    |- AsyncTermCpsTree
 *     |    |- MapCpsTree
 *     |    |- FlatMapCpsTree
 *     |- SeqCpsTree
 *     |- LambdaCpsTree
 *     |- CallChainSubstCpsTree
 */
sealed trait CpsTree {

  /** Original tree, used for diagnostics and span */
  def origin: Tree

  /** Owner symbol */
  def owner: Symbol

  /** The async kind of this tree */
  def asyncKind(using Context, CpsTransformContext): AsyncKind

  /** Original type (before transformation) */
  def originType(using Context): Type = origin.tpe

  /**
   * Get the untransformed (sync) tree if this is a sync expression.
   * Returns None for async expressions.
   */
  def unpure(using Context, CpsTransformContext): Option[Tree]

  /**
   * Get the transformed tree (F[T] form).
   * For sync expressions, wraps in monad.pure().
   * For async expressions, returns the already-transformed tree.
   */
  def transformed(using Context, CpsTransformContext): Tree

  /**
   * The transformed type: CpsTransformedType(F, originType)
   */
  def transformedType(using Context, CpsTransformContext): Type =
    CpsTransformHelper.cpsTransformedType(originType, tctx.monadType)

  /**
   * Append another CpsTree in a block: { this; next }
   */
  def appendInBlock(next: CpsTree)(using Context, CpsTransformContext): CpsTree = {
    asyncKind match {
      case AsyncKind.Sync =>
        SeqCpsTree(EmptyTree, owner, IndexedSeq(this), next.changeOwner(owner))
      case AsyncKind.Async(_) =>
        next.asyncKind match {
          case AsyncKind.Sync | AsyncKind.AsyncLambda(_) =>
            MapCpsTree(EmptyTree, owner, this, MapCpsTreeArg(None, next))
          case AsyncKind.Async(_) =>
            FlatMapCpsTree(EmptyTree, owner, this, FlatMapCpsTreeArg(None, next))
        }
      case AsyncKind.AsyncLambda(_) =>
        // TODO: emit warning about unused async lambda
        report.warning("Unused async lambda in block", origin.srcPos)
        SeqCpsTree(EmptyTree, owner, IndexedSeq(this), next.changeOwner(owner))
    }
  }

  /**
   * Change the owner of this tree.
   */
  def changeOwner(newOwner: Symbol)(using Context): CpsTree

  /**
   * Create a copy with a new origin.
   */
  def withOrigin(tree: Tree): CpsTree

  /**
   * Show for debugging.
   */
  def show(using Context): String

  /** Helper to get the transform context */
  protected def tctx(using ctx: CpsTransformContext): CpsTransformContext = ctx
}

object CpsTree {

  /** Create a pure (unchanged) CpsTree */
  def unchangedPure(origin: Tree, owner: Symbol): PureCpsTree =
    PureCpsTree(origin, owner, origin)

  /** Create a pure CpsTree with a changed term */
  def pure(origin: Tree, owner: Symbol, term: Tree): PureCpsTree =
    PureCpsTree(origin, owner, term)

  /** Create an async CpsTree */
  def async(origin: Tree, owner: Symbol, transformed: Tree, internalKind: AsyncKind = AsyncKind.Sync): AsyncTermCpsTree =
    AsyncTermCpsTree(origin, owner, transformed, internalKind)

  /** Create a unit CpsTree */
  def unit(owner: Symbol)(using Context): UnitCpsTree =
    UnitCpsTree(Literal(Constant(())), owner)
}

// ============================================================================
// Sync CpsTrees
// ============================================================================

/**
 * Base trait for sync (pure) CpsTrees.
 */
sealed trait SyncCpsTree extends CpsTree {

  override def asyncKind(using Context, CpsTransformContext): AsyncKind = AsyncKind.Sync

  /** Get the sync tree - always succeeds for SyncCpsTree */
  def getUnpure(using Context, CpsTransformContext): Tree

  override def unpure(using Context, CpsTransformContext): Option[Tree] = Some(getUnpure)

  override def transformed(using Context, CpsTransformContext): Tree = {
    // monad.pure(getUnpure)
    val pureName = "pure".toTermName
    Apply(
      TypeApply(
        Select(tctx.monadRef, pureName),
        List(TypeTree(originType.widen))
      ),
      List(getUnpure)
    ).withSpan(origin.span)
  }
}

/**
 * A pure expression that doesn't need transformation.
 */
case class PureCpsTree(
  override val origin: Tree,
  override val owner: Symbol,
  term: Tree
) extends SyncCpsTree {

  override def originType(using Context): Type = term.tpe

  override def getUnpure(using Context, CpsTransformContext): Tree = term

  override def appendInBlock(next: CpsTree)(using Context, CpsTransformContext): CpsTree =
    SeqCpsTree(origin, owner, IndexedSeq(this), next.changeOwner(owner))

  override def changeOwner(newOwner: Symbol)(using Context): CpsTree = {
    if (origin eq term) {
      val nTerm = term.changeOwner(owner, newOwner)
      PureCpsTree(nTerm, newOwner, nTerm)
    } else {
      PureCpsTree(origin.changeOwner(owner, newOwner), newOwner, term.changeOwner(owner, newOwner))
    }
  }

  override def withOrigin(tree: Tree): CpsTree = copy(origin = tree)

  override def show(using Context): String = s"PureCpsTree(${term.show})"
}

/**
 * A unit expression - optimized handling for ().
 */
case class UnitCpsTree(
  override val origin: Tree,
  override val owner: Symbol
) extends SyncCpsTree {

  override def getUnpure(using Context, CpsTransformContext): Tree = origin

  override def appendInBlock(next: CpsTree)(using Context, CpsTransformContext): CpsTree = next

  override def changeOwner(newOwner: Symbol)(using Context): CpsTree = copy(owner = newOwner)

  override def withOrigin(tree: Tree): CpsTree = copy(origin = tree)

  override def show(using Context): String = "UnitCpsTree"
}

// ============================================================================
// Async CpsTrees
// ============================================================================

/**
 * Base trait for async CpsTrees.
 */
sealed trait AsyncCpsTree extends CpsTree {

  def internalAsyncKind(using Context, CpsTransformContext): AsyncKind

  override def asyncKind(using Context, CpsTransformContext): AsyncKind =
    AsyncKind.Async(internalAsyncKind)

  override def unpure(using Context, CpsTransformContext): Option[Tree] = None
}

/**
 * An async expression that's already transformed to F[T].
 */
case class AsyncTermCpsTree(
  override val origin: Tree,
  override val owner: Symbol,
  transformedTree: Tree,
  vInternalAsyncKind: AsyncKind
) extends AsyncCpsTree {

  override def internalAsyncKind(using Context, CpsTransformContext): AsyncKind = vInternalAsyncKind

  override def transformed(using Context, CpsTransformContext): Tree = transformedTree

  override def appendInBlock(next: CpsTree)(using Context, CpsTransformContext): CpsTree = {
    next.asyncKind match {
      case AsyncKind.Async(_) =>
        FlatMapCpsTree(origin, owner, this, FlatMapCpsTreeArg(None, next))
      case _ =>
        MapCpsTree(origin, owner, this, MapCpsTreeArg(None, next))
    }
  }

  override def changeOwner(newOwner: Symbol)(using Context): CpsTree =
    copy(owner = newOwner, transformedTree = transformedTree.changeOwner(owner, newOwner))

  override def withOrigin(tree: Tree): CpsTree = copy(origin = tree)

  override def show(using Context): String =
    s"AsyncTermCpsTree(${transformedTree.show}, kind=${vInternalAsyncKind})"
}

/**
 * A map operation: source.map(x => body)
 */
case class MapCpsTree(
  override val origin: Tree,
  override val owner: Symbol,
  source: CpsTree,
  mapArg: MapCpsTreeArg
)(using Context, CpsTransformContext) extends AsyncCpsTree {

  override def originType(using Context): Type = mapArg.body.originType

  override def internalAsyncKind(using Context, CpsTransformContext): AsyncKind = {
    source.asyncKind match {
      case AsyncKind.Sync => AsyncKind.Sync
      case AsyncKind.Async(internal) => internal
      case AsyncKind.AsyncLambda(_) =>
        throw CpsTransformException("Lambda can't be map source", origin.srcPos)
    }
  }

  override def transformed(using Context, CpsTransformContext): Tree = {
    source.unpure match {
      case Some(unpureTerm) =>
        // Source is sync - just apply the function
        Apply(mapArg.makeLambda(this), List(unpureTerm)).withSpan(origin.span)
      case None =>
        // Source is async - use monad.map
        val mapName = "map".toTermName
        Apply(
          Apply(
            TypeApply(
              Select(tctx.monadRef, mapName),
              List(TypeTree(source.originType.widen), TypeTree(originType.widen))
            ),
            List(source.transformed)
          ),
          List(mapArg.makeLambda(this))
        ).withSpan(origin.span)
    }
  }

  override def appendInBlock(next: CpsTree)(using Context, CpsTransformContext): CpsTree = {
    next.asyncKind match {
      case AsyncKind.Async(_) =>
        FlatMapCpsTree(origin, owner, source, FlatMapCpsTreeArg(mapArg.optParam, mapArg.body.appendInBlock(next)))
      case _ =>
        MapCpsTree(origin, owner, source, MapCpsTreeArg(mapArg.optParam, mapArg.body.appendInBlock(next)))
    }
  }

  override def changeOwner(newOwner: Symbol)(using Context): CpsTree =
    copy(owner = newOwner, source = source.changeOwner(newOwner))

  override def withOrigin(tree: Tree): CpsTree = copy(origin = tree)

  override def show(using Context): String = s"MapCpsTree(${source.show}, ${mapArg.show})"
}

/**
 * Argument for MapCpsTree - the lambda parameter and body.
 */
case class MapCpsTreeArg(optParam: Option[ValDef], body: CpsTree) {

  def makeLambda(mapCpsTree: MapCpsTree)(using Context, CpsTransformContext): Block = {
    body.unpure match {
      case None =>
        throw CpsTransformException("MapCpsTree body must be sync - use FlatMapCpsTree for async", body.origin.srcPos)
      case Some(syncBody) =>
        val owner = mapCpsTree.owner
        val param = optParam.getOrElse {
          val sym = newSymbol(owner, "_unused".toTermName, EmptyFlags, mapCpsTree.source.originType.widen, NoSymbol)
          ValDef(sym, EmptyTree)
        }
        TransformUtil.makeLambda(List(param), body.originType.widen, owner, syncBody, body.owner)
    }
  }

  def show(using Context): String = s"[${optParam.map(_.name).getOrElse("_")} => ${body.show}]"
}

/**
 * A flatMap operation: source.flatMap(x => body)
 */
case class FlatMapCpsTree(
  override val origin: Tree,
  override val owner: Symbol,
  source: CpsTree,
  flatMapArg: FlatMapCpsTreeArg
) extends AsyncCpsTree {

  override def originType(using Context): Type = flatMapArg.body.originType

  override def internalAsyncKind(using Context, CpsTransformContext): AsyncKind = {
    flatMapArg.body.asyncKind match {
      case AsyncKind.Sync => AsyncKind.Sync
      case AsyncKind.Async(internal) => internal
      case AsyncKind.AsyncLambda(_) =>
        throw CpsTransformException("FlatMap body should not be lambda", origin.srcPos)
    }
  }

  override def transformed(using Context, CpsTransformContext): Tree = {
    val flatMapName = "flatMap".toTermName
    Apply(
      Apply(
        TypeApply(
          Select(tctx.monadRef, flatMapName),
          List(TypeTree(source.originType.widen), TypeTree(originType.widen))
        ),
        List(source.transformed)
      ),
      List(flatMapArg.makeLambda(this))
    ).withSpan(origin.span)
  }

  override def appendInBlock(next: CpsTree)(using Context, CpsTransformContext): CpsTree = {
    FlatMapCpsTree(origin, owner, source, FlatMapCpsTreeArg(flatMapArg.optParam, flatMapArg.body.appendInBlock(next)))
  }

  override def changeOwner(newOwner: Symbol)(using Context): CpsTree =
    copy(owner = newOwner, source = source.changeOwner(newOwner))

  override def withOrigin(tree: Tree): CpsTree = copy(origin = tree)

  override def show(using Context): String = s"FlatMapCpsTree(${source.show}, ${flatMapArg.show})"
}

/**
 * Argument for FlatMapCpsTree - the lambda parameter and body.
 */
case class FlatMapCpsTreeArg(optParam: Option[ValDef], body: CpsTree) {

  def makeLambda(flatMapCpsTree: FlatMapCpsTree)(using Context, CpsTransformContext): Block = {
    val owner = flatMapCpsTree.owner
    val param = optParam.getOrElse {
      val sym = newSymbol(owner, "_unused".toTermName, EmptyFlags, flatMapCpsTree.source.originType.widen, NoSymbol)
      ValDef(sym, EmptyTree)
    }
    val transformedBody = body.transformed
    TransformUtil.makeLambda(List(param), body.transformedType, owner, transformedBody, body.owner)
  }

  def show(using Context): String = s"[${optParam.map(_.name).getOrElse("_")} => ${body.show}]"
}

// ============================================================================
// Sequence CpsTree
// ============================================================================

/**
 * A sequence of statements followed by an expression: { stmt1; stmt2; ...; expr }
 * Only the last element (expr) can be async.
 */
case class SeqCpsTree(
  override val origin: Tree,
  override val owner: Symbol,
  prevs: IndexedSeq[CpsTree],
  last: CpsTree
) extends CpsTree {

  override def asyncKind(using Context, CpsTransformContext): AsyncKind = last.asyncKind

  override def originType(using Context): Type = last.originType

  override def unpure(using Context, CpsTransformContext): Option[Tree] = {
    if (prevs.isEmpty) {
      last.unpure
    } else {
      last.asyncKind match {
        case AsyncKind.Sync =>
          val stats = prevs.map(t => t.unpure.get.changeOwner(t.owner, owner)).toList
          Some(Block(stats, last.unpure.get.changeOwner(last.owner, owner)).withSpan(origin.span))
        case _ =>
          None
      }
    }
  }

  override def transformed(using Context, CpsTransformContext): Tree = {
    if (prevs.isEmpty) {
      last.transformed
    } else {
      val stats = prevs.map(t => t.unpure.get.changeOwner(t.owner, owner)).toList
      val lastTransformed = last.transformed.changeOwner(last.owner, owner)
      Block(stats, lastTransformed).withSpan(origin.span)
    }
  }

  override def appendInBlock(next: CpsTree)(using Context, CpsTransformContext): CpsTree = {
    last.asyncKind match {
      case AsyncKind.Sync =>
        SeqCpsTree(origin, owner, prevs :+ last, next)
      case AsyncKind.Async(_) =>
        SeqCpsTree(origin, owner, prevs, last.appendInBlock(next))
      case AsyncKind.AsyncLambda(_) =>
        throw CpsTransformException("Unused AsyncLambda", origin.srcPos)
    }
  }

  override def changeOwner(newOwner: Symbol)(using Context): CpsTree =
    copy(owner = newOwner, prevs = prevs.map(_.changeOwner(newOwner)), last = last.changeOwner(newOwner))

  override def withOrigin(tree: Tree): CpsTree = copy(origin = tree)

  override def show(using Context): String = {
    val prevsStr = prevs.map(_.show).mkString(", ")
    s"SeqCpsTree([$prevsStr], ${last.show})"
  }
}

// ============================================================================
// Lambda CpsTree
// ============================================================================

/**
 * A lambda expression: (params) => body
 */
case class LambdaCpsTree(
  override val origin: Tree,
  override val owner: Symbol,
  originDefDef: DefDef,
  cpsBody: CpsTree
) extends CpsTree {

  override def asyncKind(using Context, CpsTransformContext): AsyncKind =
    AsyncKind.AsyncLambda(cpsBody.asyncKind)

  override def unpure(using Context, CpsTransformContext): Option[Tree] = {
    cpsBody.unpure.map { unpureBody =>
      // Create a new lambda with the unpure body
      val tpe = originDefDef.tpe.widen
      val meth = Symbols.newAnonFun(owner, tpe)
      Closure(
        meth,
        tss => TransformUtil.substParams(unpureBody, originParams, tss.head).changeOwner(cpsBody.owner, meth),
        Nil,
        NoType
      )
    }
  }

  override def transformed(using Context, CpsTransformContext): Tree = {
    // Create a new lambda with the transformed body
    val tpe = createShiftedType()
    val meth = Symbols.newAnonFun(owner, tpe)
    Closure(
      meth,
      tss => TransformUtil.substParams(cpsBody.transformed, originParams, tss.head).changeOwner(cpsBody.owner, meth),
      Nil,
      NoType
    )
  }

  override def appendInBlock(next: CpsTree)(using Context, CpsTransformContext): CpsTree =
    SeqCpsTree(EmptyTree, owner, IndexedSeq(this), next.changeOwner(owner))

  override def changeOwner(newOwner: Symbol)(using Context): CpsTree =
    copy(owner = newOwner)

  override def withOrigin(tree: Tree): CpsTree = copy(origin = tree)

  override def show(using Context): String = s"LambdaCpsTree(${originDefDef.name}, ${cpsBody.show})"

  private def originParams(using Context): List[ValDef] = originDefDef.termParamss.head

  private def createShiftedType()(using Context, CpsTransformContext): Type = {
    val params = originDefDef.termParamss.head
    val paramNames = params.map(_.name)
    val paramTypes = params.map(_.tpe.widen)
    MethodType(paramNames)(
      _ => paramTypes,
      _ => cpsBody.transformedType.widen
    )
  }
}

// ============================================================================
// Call Chain Substitution CpsTree
// ============================================================================

/**
 * A call-chain substitution expression.
 * Used for lazy operations like withFilter that need to be finished later.
 */
case class CallChainSubstCpsTree(
  override val origin: Tree,
  override val owner: Symbol,
  call: CpsTree
) extends CpsTree {

  override def asyncKind(using Context, CpsTransformContext): AsyncKind = finishChain().asyncKind

  override def unpure(using Context, CpsTransformContext): Option[Tree] = finishChain().unpure

  override def transformed(using Context, CpsTransformContext): Tree = finishChain().transformed

  override def appendInBlock(next: CpsTree)(using Context, CpsTransformContext): CpsTree =
    finishChain().appendInBlock(next)

  override def changeOwner(newOwner: Symbol)(using Context): CpsTree =
    copy(owner = newOwner, call = call.changeOwner(newOwner))

  override def withOrigin(tree: Tree): CpsTree = copy(origin = tree)

  override def show(using Context): String = s"CallChainSubstCpsTree(${call.show})"

  /**
   * Finish the call chain by calling _finishChain on the delayed wrapper.
   */
  def finishChain()(using Context, CpsTransformContext): CpsTree = {
    // TODO: implement proper call chain finishing
    // For now, return the call as-is
    call
  }
}

// ============================================================================
// Block Bounds CpsTree
// ============================================================================

/**
 * Wrapper that preserves lexical scoping boundaries.
 * Delegates to internal for all operations.
 */
case class BlockBoundsCpsTree(internal: CpsTree) extends CpsTree {

  override def origin: Tree = internal.origin
  override def owner: Symbol = internal.owner
  override def originType(using Context): Type = internal.originType
  override def asyncKind(using Context, CpsTransformContext): AsyncKind = internal.asyncKind
  override def unpure(using Context, CpsTransformContext): Option[Tree] = internal.unpure
  override def transformed(using Context, CpsTransformContext): Tree = internal.transformed

  override def changeOwner(newOwner: Symbol)(using Context): CpsTree =
    BlockBoundsCpsTree(internal.changeOwner(newOwner))

  override def withOrigin(tree: Tree): CpsTree =
    BlockBoundsCpsTree(internal.withOrigin(tree))

  override def show(using Context): String = s"BlockBoundsCpsTree(${internal.show})"
}

// ============================================================================
// Utility
// ============================================================================

/**
 * Exception thrown during CPS transformation.
 */
case class CpsTransformException(message: String, srcPos: util.SrcPos) extends Exception(message)

/**
 * Utility methods for tree transformation.
 */
object TransformUtil {

  /**
   * Create a lambda block: { def anon(params): resType = body; closure(anon) }
   */
  def makeLambda(params: List[ValDef], resType: Type, owner: Symbol, body: Tree, bodyOwner: Symbol)(using Context): Block = {
    val mt = MethodType(params.map(_.name))(
      _ => params.map(_.tpe.widen),
      _ => resType
    )
    val meth = Symbols.newAnonFun(owner, mt)
    val ddef = DefDef(meth, { paramss =>
      body.changeOwner(bodyOwner, meth)
    })
    val closure = Closure(meth, _ => ref(meth))
    Block(List(ddef), closure)
  }

  /**
   * Substitute parameter references in a tree.
   */
  def substParams(tree: Tree, oldParams: List[ValDef], newArgs: List[Tree])(using Context): Tree = {
    val substitution = oldParams.map(_.symbol).zip(newArgs).toMap
    // Use TreeMap for substitution
    val mapper = new TreeMap {
      override def transform(t: Tree)(using Context): Tree = t match {
        case id: Ident if substitution.contains(id.symbol) => substitution(id.symbol)
        case _ => super.transform(t)
      }
    }
    mapper.transform(tree)
  }
}
