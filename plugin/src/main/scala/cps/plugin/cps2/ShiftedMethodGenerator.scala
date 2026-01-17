package cps.plugin.cps2

import dotty.tools.dotc.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.util.SrcPos
import dotty.tools.dotc.config.Feature
import core.*
import core.Names.*
import core.NameOps.*
import core.StdNames
import core.StdNames.nme
import core.Contexts.*
import core.Decorators.*
import core.Symbols.*
import core.SymDenotations.{SymDenotation, LazyType}
import core.Types.*
import core.Flags.*
import core.Scopes.{newScope, MutableScope}
import core.Annotations.Annotation
import ast.untpd
import ast.TreeTypeMap

/**
 * Generates shifted (async) versions of higher-order methods.
 *
 * For a method like:
 *   def map[A, B](f: A => B): List[B]
 *
 * Generates:
 *   def map_async[F_SHIFT[_], A, B](m: CpsTryMonad[F_SHIFT])(f: A => F_SHIFT[B]): F_SHIFT[List[B]]
 *
 * For call-chain methods (like withFilter) that STORE the function:
 *   def withFilter(p: A => Boolean): WithFilter[A, C]
 *
 * Generates (with DelayedWithFilter instead of F[WithFilter]):
 *   def withFilter_async[F_SHIFT[_]](m: CpsTryMonad[F_SHIFT])(p: A => F_SHIFT[Boolean]): DelayedWithFilter[F_SHIFT, A, C]
 *
 * Only generates for methods with IMPURE function parameters (can capture capabilities).
 * Pure functions (A -> B) don't need shifting as they cannot capture.
 *
 * Minimal port from dotty-cps-async ShiftedMethodGenerator.
 */
object ShiftedMethodGenerator {

  /** Known types that are call-chain intermediates (store functions for lazy evaluation) */
  private val knownCallChainTypes = Set(
    "scala.collection.WithFilter",
    "scala.collection.IterableOps.WithFilter",
    "scala.collection.MapOps.WithFilter",
    "scala.collection.SortedMapOps.WithFilter",
    "scala.collection.Iterator",
    "scala.collection.View",
    "scala.collection.SeqView",
    "scala.collection.MapView",
    "scala.collection.IndexedSeqView",
  )

  /**
   * Mapping from known call-chain types to their delayed wrapper types.
   * The delayed wrapper is looked up in cps.runtime package.
   */
  private val knownDelayedTypes = Map(
    "scala.collection.WithFilter" -> "cps.runtime.DelayedWithFilter",
    "scala.collection.IterableOps.WithFilter" -> "cps.runtime.DelayedWithFilter",
    // Add more as library grows
  )

  /**
   * Result of analyzing a method for shifted generation.
   */
  enum ShiftedMethodKind:
    case Standard           // Normal HOF: return F[T]
    case CallChain          // Stores function: return DelayedXxx instead of F[T]
    case NotApplicable      // No impure function params

  /**
   * Generate a shifted version of a higher-order method.
   * Returns None if the method doesn't have impure function parameters.
   */
  def generateShiftedMethod(md: DefDef)(using Context): Option[DefDef] = {
    analyzeMethod(md) match
      case ShiftedMethodKind.NotApplicable =>
        None

      case ShiftedMethodKind.CallChain =>
        // Generate with delayed return type lookup
        val returnType = getMethodReturnType(md)
        println(s"CPS2 Plugin: Detected call-chain method ${md.name} returning ${returnType.show}")
        generateCallChainShiftedMethod(md)

      case ShiftedMethodKind.Standard =>
        generateStandardShiftedMethod(md)
  }

  /**
   * Analyze a method to determine what kind of shifted version it needs.
   */
  def analyzeMethod(md: DefDef)(using Context): ShiftedMethodKind = {
    if (!hasImpureFunctionParams(md)) {
      ShiftedMethodKind.NotApplicable
    } else if (needsCallChainSubstitution(md)) {
      ShiftedMethodKind.CallChain
    } else {
      ShiftedMethodKind.Standard
    }
  }

  /**
   * Generate the standard shifted method (returns F[T]).
   */
  private def generateStandardShiftedMethod(md: DefDef)(using Context): Option[DefDef] = {
    val shiftedType = shiftedFunctionPolyType(md, isCallChain = false)
    val shiftedFunName = (md.symbol.name.toString + "_async").toTermName

    val newFunSymbol = Symbols.newSymbol(
      owner = md.symbol.owner,
      name = shiftedFunName,
      flags = md.symbol.flags | Synthetic,
      info = shiftedType
    ).entered.asTerm

    // Get function parameters that need await insertion
    val functionParams = selectFunctionParams(md.paramss)

    Some(DefDef(newFunSymbol, newParamss => {
      given Context = summon[Context].withOwner(newFunSymbol)

      // Extract type params and monad param from new paramss
      val paramsInfo = buildShiftedParamsInfo(md, newParamss)

      // Original return type - substitute type and term params to use new method's params
      val originalReturnType = substituteAllParams(getMethodReturnType(md), paramsInfo)

      // CPS transformed return type
      val cpsReturnType = CpsTransformHelper.cpsTransformedType(originalReturnType, paramsInfo.fShiftType)

      // Transform original body: substitute params and insert await for function calls
      val transformedBody = transformBodyWithAwait(md.rhs, functionParams, paramsInfo.valueParamMap, paramsInfo.fShiftType, paramsInfo)

      // Wrap in cpsPhase1Scaffold[F, T, R](m, transformedBody)
      generateScaffoldCall(paramsInfo.fShiftType, originalReturnType, cpsReturnType, paramsInfo.monadRef, transformedBody)
    }))
  }

  /**
   * Generate the call-chain shifted method.
   *
   * For call-chain methods, the return type is a delayed wrapper instead of F[T].
   * The body should construct the delayed class directly, passing async function params.
   *
   * Example - Original:
   *   def lazyMapA[A, B](list: List[A])(f: A => B): LazyA[A, B]^{f} = LazyA(list, f)
   *
   * Generated:
   *   def lazyMapA_async[F_SHIFT[_], A, B](m: CpsTryMonad[F_SHIFT])(list: List[A])(f: A => F_SHIFT[B]): LazyA_cps[F_SHIFT, A, B] =
   *     new LazyA_cps(m, list, f)  // Pass f directly to delayed class
   */
  private def generateCallChainShiftedMethod(md: DefDef)(using Context): Option[DefDef] = {
    val shiftedType = shiftedFunctionPolyType(md, isCallChain = true)
    val shiftedFunName = (md.symbol.name.toString + "_async").toTermName

    val newFunSymbol = Symbols.newSymbol(
      owner = md.symbol.owner,
      name = shiftedFunName,
      flags = md.symbol.flags | Synthetic,
      info = shiftedType
    ).entered.asTerm

    val defdef = DefDef(newFunSymbol, newParamss => {
      given Context = summon[Context].withOwner(newFunSymbol)

      // Extract type params and monad param from new paramss
      val paramsInfo = buildShiftedParamsInfo(md, newParamss)

      // Original return type - substitute type and term params to use new method's params
      val originalReturnType = substituteAllParams(getMethodReturnType(md), paramsInfo)

      // Find the delayed class for the return type
      val delayedType = getCallChainReturnType(originalReturnType, paramsInfo.fShiftType)

      // Generate body that constructs the delayed class directly
      generateCallChainBody(md.rhs, delayedType, paramsInfo)
    })
    println(s"=== Generated call-chain shifted method ${shiftedFunName} ===")
    println(defdef.show)
    println(s"=== End ${shiftedFunName} ===")
    Some(defdef)
  }

  /**
   * Generate body for call-chain shifted method: construct delayed class directly.
   *
   * Transforms the original body which constructs the original class:
   *   LazyA(list, f) or new LazyA(list, f)
   *
   * Into construction of the delayed class:
   *   new LazyA_cps(m, list, f)
   *
   * Where:
   * - m is the monad param
   * - Non-function params are passed through (substituted to new method's params)
   * - Function params are passed directly (already have async type A => F[B])
   */
  private def generateCallChainBody(
    originalBody: Tree,
    delayedType: Type,
    paramsInfo: ShiftedParamsInfo
  )(using Context): Tree = {
    // Check if this is a simple constructor call that can be directly transformed
    extractConstructorCall(originalBody, delayedType) match {
      case Some((args, typeArgs)) =>
        generateDelayedConstructorCall(originalBody, args, delayedType, paramsInfo)

      case None =>
        // Complex case (method chains, conditionals, etc.)
        // For now, use ??? placeholder - Phase 2 will transform this
        println(s"DEBUG generateCallChainBody: Complex body detected, using placeholder for: ${originalBody.show.take(50)}")
        // The placeholder has the correct return type but no real implementation
        ref(defn.Predef_undefined)
    }
  }

  /**
   * Try to extract a simple constructor call from the body.
   * Returns Some((args, typeArgs)) if body is `new Class(args)` or `Class.apply(args)`,
   * where the Class matches the delayed type's original class.
   */
  private def extractConstructorCall(body: Tree, delayedType: Type)(using Context): Option[(List[Tree], List[Type])] = {
    // Get the expected original class from the delayed type
    val expectedOriginalClass: Option[Symbol] = delayedType match {
      case AppliedType(tycon, _) =>
        val delayedName = tycon.typeSymbol.name.toString
        if (delayedName.endsWith("_cps")) {
          val origName = delayedName.dropRight(4).toTypeName
          val origSym = tycon.typeSymbol.owner.info.member(origName).symbol
          if (origSym.exists && origSym.isClass) Some(origSym) else None
        } else None
      case _ => None
    }

    body match {
      case Apply(fun, args) =>
        // Check if fun is a constructor or apply method of the expected class
        extractClassFromFun(fun) match {
          case Some(classSym) if expectedOriginalClass.contains(classSym) =>
            // Extract type args if present
            val typeArgs = fun match {
              case TypeApply(_, targs) => targs.map(_.tpe)
              case _ => Nil
            }
            Some((args, typeArgs))
          case _ =>
            // Not a simple constructor call to the expected class
            None
        }

      case Block(Nil, expr) =>
        // Empty block - just extract from the expression
        extractConstructorCall(expr, delayedType)

      case _ =>
        None
    }
  }

  /**
   * Extract the class symbol from a constructor/apply method call.
   */
  private def extractClassFromFun(fun: Tree)(using Context): Option[Symbol] = {
    fun match {
      case Select(New(tpt), _) =>
        // new Class(...)
        Some(tpt.tpe.typeSymbol)

      case Select(qual, name) if name == nme.apply =>
        // Class.apply(...) - qual is the companion object
        qual.tpe.typeSymbol.companionClass match {
          case cls if cls.exists => Some(cls)
          case _ => None
        }

      case TypeApply(inner, _) =>
        // new Class[T](...) or Class[T](...)
        extractClassFromFun(inner)

      case _ =>
        None
    }
  }

  /**
   * Generate call-chain body using scaffold wrapping for complex cases.
   */
  private def generateCallChainBodyWithScaffold(
    originalBody: Tree,
    delayedType: Type,
    paramsInfo: ShiftedParamsInfo
  )(using Context): Tree = {
    // Get function parameters that need await insertion
    // For call-chain methods, we need to wrap function params with await lambdas
    // (unlike direct constructor calls where we pass async params directly)
    val functionParams = paramsInfo.valueParamMap.keys.filter { sym =>
      isAnyFunction(sym.info)
    }.toSet

    // Transform body with await insertion for function calls
    val transformedBody = transformBodyForCallChain(originalBody, functionParams, paramsInfo)

    // Reconstruct original return type
    val originalReturnType = delayedType match {
      case AppliedType(tycon, fShift :: rest) =>
        val delayedName = tycon.typeSymbol.name.toString
        if (delayedName.endsWith("_cps")) {
          val origName = delayedName.dropRight(4).toTypeName
          val origSym = tycon.typeSymbol.owner.info.member(origName).symbol
          if (origSym.exists && origSym.isClass) {
            if (rest.isEmpty) origSym.typeRef
            else origSym.typeRef.appliedTo(rest)
          } else {
            originalBody.tpe.widen
          }
        } else {
          originalBody.tpe.widen
        }
      case _ =>
        originalBody.tpe.widen
    }

    println(s"DEBUG generateCallChainBodyWithScaffold: originalReturnType=${originalReturnType.show}, delayedType=${delayedType.show}")
    println(s"DEBUG generateCallChainBodyWithScaffold: transformedBody=${transformedBody.show}")
    generateScaffoldCall(paramsInfo.fShiftType, originalReturnType, delayedType, paramsInfo.monadRef, transformedBody)
  }

  /**
   * Transform body for call-chain method with scaffold wrapping.
   * Similar to transformBodyWithAwait but for call-chain context.
   * Properly handles nested lambdas by recreating their method symbols.
   */
  private def transformBodyForCallChain(
    body: Tree,
    functionParamSyms: Set[Symbol],
    paramsInfo: ShiftedParamsInfo
  )(using Context): Tree = {
    val mapper = new TreeMap {
      override def transform(tree: Tree)(using Context): Tree = {
        tree match {
          // Lambda: Block(List(DefDef), Closure) - must recreate with new method symbol
          case block @ Block((ddef: DefDef) :: Nil, closure: Closure) =>
            println(s"DEBUG transformBodyForCallChain: Transforming lambda: ${ddef.name}")
            // Get the lambda's method type and substitute types
            val oldMt = ddef.tpe.widen.asInstanceOf[MethodType]
            val newParamTypes = oldMt.paramInfos.map(t => substituteAllParams(t, paramsInfo))
            val newResultType = substituteAllParams(oldMt.resultType, paramsInfo)
            val newMt = MethodType(oldMt.paramNames)(_ => newParamTypes, _ => newResultType)

            // Create new method symbol with substituted types
            val owner = summon[Context].owner
            val newMethSym = Symbols.newAnonFun(owner, newMt)

            // Build new lambda using Closure
            Closure(
              newMethSym,
              { tss =>
                val oldParams = ddef.paramss.flatten.collect { case vd: ValDef => vd }
                val newParams = tss.head

                val oldSymbols = oldParams.map(_.symbol)
                val newSymbols = newParams.map(_.symbol)
                val (oldTypeSyms, newTypes) = paramsInfo.typeSymbolSubst.toList.unzip

                val treeMap = new TreeTypeMap(
                  oldOwners = List(ddef.symbol),
                  newOwners = List(newMethSym),
                  substFrom = oldSymbols ++ oldTypeSyms,
                  substTo = newSymbols ++ newTypes.map(_.typeSymbol)
                )

                val bodyCtx = summon[Context].withOwner(newMethSym)
                val transformedBody = transform(ddef.rhs)(using bodyCtx)
                treeMap.transform(transformedBody)
              }
            )

          // Function param call: f(x) -> scaffoldAwait(f(x))
          case app: Apply =>
            extractFunctionParamFromCall(app, functionParamSyms) match {
              case Some(funcParamIdent) =>
                // Transform with await
                val transformedApp = transformAppWithFuncParamSubst(app, funcParamIdent.symbol, paramsInfo.valueParamMap, paramsInfo)
                insertAwait(transformedApp, paramsInfo.fShiftType)
              case None =>
                super.transform(tree)
            }

          // Substitute param references
          case id: Ident if paramsInfo.valueParamMap.contains(id.symbol) =>
            val newParam = paramsInfo.valueParamMap(id.symbol)
            if (functionParamSyms.contains(id.symbol)) {
              // Function param used as value: wrap with await lambda
              wrapFunctionWithAwait(newParam, id.tpe, paramsInfo.fShiftType, paramsInfo)
            } else {
              newParam
            }

          case tt: TypeTree =>
            val newType = substituteAllParams(tt.tpe, paramsInfo)
            if (newType ne tt.tpe) TypeTree(newType) else tt

          case _ =>
            super.transform(tree)
        }
      }
    }
    mapper.transform(body)
  }

  /**
   * Generate constructor call for delayed class.
   *
   * Note: For simple call-chain methods like `def lazyMapA(list, f) = LazyA(list, f)`,
   * we construct the delayed class directly. The key insight is that `f` already has
   * the async type `A => F[B]` in the shifted method, so we just pass it through.
   *
   * However, some methods have complex args (like lambdas that use params). For those,
   * we fall back to scaffold wrapping since the body transformation is complex.
   */
  private def generateDelayedConstructorCall(
    originalBody: Tree,
    originalArgs: List[Tree],
    delayedType: Type,
    paramsInfo: ShiftedParamsInfo
  )(using Context): Tree = {
    val delayedTypeSym = delayedType.typeSymbol

    // Check if the delayed type is a valid class (not a forward reference)
    if (!delayedTypeSym.isClass) {
      // Forward reference case - the delayed class doesn't exist yet
      // Fall back to scaffold wrapping which will be handled in Phase 2
      println(s"DEBUG generateDelayedConstructorCall: Forward reference detected for ${delayedType.show}, falling back to scaffold")
      return generateCallChainBodyWithScaffold(originalBody, delayedType, paramsInfo)
    }

    // Check if args are "simple" (just param references) - if not, use scaffold
    val hasComplexArgs = originalArgs.exists { arg =>
      arg match {
        case _: Ident => false  // Simple param reference - OK
        case _ => true  // Complex expression - needs scaffold
      }
    }

    if (hasComplexArgs) {
      println(s"DEBUG generateDelayedConstructorCall: Complex args detected, falling back to scaffold")
      return generateCallChainBodyWithScaffold(originalBody, delayedType, paramsInfo)
    }

    val delayedClassSym = delayedTypeSym.asClass

    // Get type args for the delayed class (F_SHIFT :: originalTypeArgs)
    val typeArgs = delayedType match {
      case AppliedType(_, args) => args
      case _ => List(paramsInfo.fShiftType)
    }

    // Transform original args: substitute params (function params keep their async type)
    val transformedArgs = originalArgs.map { arg =>
      substituteParamsInTree(arg, paramsInfo)
    }

    // Build: new DelayedClass[F_SHIFT, ...](m, transformedArgs...)
    val appliedType = if (typeArgs.nonEmpty) {
      delayedClassSym.typeRef.appliedTo(typeArgs)
    } else {
      delayedClassSym.typeRef
    }
    println(s"DEBUG generateDelayedConstructorCall: appliedType=${appliedType.show}, typeArgs=${typeArgs.map(_.show)}")

    // Constructor args: monad first, then transformed original args
    val ctorArgs = paramsInfo.monadRef :: transformedArgs

    // Build the constructor call following the pattern from tpd.New(tp, constr, args):
    //   New(tycon)
    //     .select(TermRef(tycon, constr))
    //     .appliedToTypes(targs)
    //     .appliedToTermArgs(args)
    //
    // Where tycon is the type constructor (without type args), targs are type args.

    val tycon = appliedType.typeConstructor  // LazyA_cps (without type args)
    val targs = appliedType.argTypes         // List(F_SHIFT, A, B)
    val constr = delayedClassSym.primaryConstructor.asTerm

    // Build: new LazyA_cps[F_SHIFT, A, B](m, list, f)
    val result = tpd.New(tycon)
      .select(TermRef(tycon, constr))
      .appliedToTypes(targs)
      .appliedToTermArgs(ctorArgs)

    // Debug: print result type
    println(s"DEBUG generateDelayedConstructorCall: result.tpe = ${result.tpe.show}")
    println(s"DEBUG generateDelayedConstructorCall: result.show = ${result.show}")

    result
  }

  /**
   * Substitute parameters in a tree (for call-chain body generation).
   * Unlike transformBodyWithAwait, this does NOT wrap function params with await.
   */
  private def substituteParamsInTree(tree: Tree, paramsInfo: ShiftedParamsInfo)(using Context): Tree = {
    val mapper = new TreeMap {
      override def transform(tree: Tree)(using Context): Tree = {
        tree match {
          case id: Ident if paramsInfo.valueParamMap.contains(id.symbol) =>
            // Substitute param reference
            paramsInfo.valueParamMap(id.symbol)

          case tt: TypeTree =>
            val newType = substituteAllParams(tt.tpe, paramsInfo)
            if (newType ne tt.tpe) TypeTree(newType) else tt

          case _ =>
            super.transform(tree)
        }
      }
    }
    mapper.transform(tree)
  }

  /**
   * Detect if a method needs CallChainAsyncShiftSubst handling.
   *
   * A method needs this if it STORES its function argument in the return type,
   * rather than applying it immediately. With capture checking, this means
   * capabilities from the function would flow into the return type's capture set.
   *
   * Detection criteria:
   * 1. Return type's class has function-typed constructor parameters (stores functions)
   * 2. OR return type is a known call-chain type (WithFilter, Iterator, View, etc.)
   */
  def needsCallChainSubstitution(md: DefDef)(using Context): Boolean = {
    val returnType = getMethodReturnType(md)
    isCallChainIntermediateType(returnType)
  }

  /**
   * Check if a type is a "call chain intermediate" - a type that stores functions
   * for lazy/deferred evaluation.
   */
  def isCallChainIntermediateType(tp: Type)(using Context): Boolean = {
    val returnType = tp.widen.dealias
    val returnSym = returnType.typeSymbol

    // Check 1: Is it a known call-chain type?
    if (knownCallChainTypes.contains(returnSym.fullName.toString)) {
      return true
    }

    // Check 2: Does the return type's class store functions?
    // (i.e., has function-typed constructor parameters)
    if (returnSym.isClass) {
      val classInfo = returnSym.asClass
      val primaryCtor = classInfo.primaryConstructor
      if (primaryCtor.exists) {
        val storesFunctions = primaryCtor.paramSymss.flatten.exists { param =>
          isAnyFunction(param.info)
        }
        if (storesFunctions) {
          return true
        }
      }
    }

    false
  }

  /**
   * Get the return type of a method, handling PolyType, MethodType, and ExprType.
   */
  def getMethodReturnType(md: DefDef)(using Context): Type = {
    def extractReturnType(tp: Type): Type = tp match
      case pt: PolyType => extractReturnType(pt.resultType)
      case mt: MethodType => extractReturnType(mt.resultType)
      case et: ExprType => et.resultType  // Unwrap by-name type (=> T)
      case other => other

    extractReturnType(md.tpe.widen)
  }

  /**
   * Substitute type and term params in a type using ShiftedParamsInfo.
   * Replaces old PolyType param refs, MethodType param refs, and type symbol refs with new refs.
   */
  def substituteAllParams(tp: Type, info: ShiftedParamsInfo)(using Context): Type = {
    var result = tp

    // First substitute type params for all PolyTypes (handles nested PolyTypes like in extensions)
    for ((oldPt, newRefs) <- info.typeParamSubsts) {
      result = result.substParams(oldPt, newRefs)
    }

    // Substitute type param symbols (handles TypeRef to type param symbols)
    if (info.typeSymbolSubst.nonEmpty) {
      val (oldSyms, newTypes) = info.typeSymbolSubst.toList.unzip
      result = result.subst(oldSyms, newTypes)
    }

    // Then substitute term params for all MethodTypes (handles curried methods)
    for ((oldMt, newTypes) <- info.termParamSubsts) {
      result = result.substParams(oldMt, newTypes)
    }

    result
  }

  /**
   * Find a delayed wrapper type for a call-chain intermediate type.
   *
   * Lookup strategy:
   * 1. Check auto-generated registry (from earlier in same compilation)
   * 2. Check known mappings (library-provided like DelayedWithFilter)
   * 3. Try naming conventions in same package: Delayed + Name, Name_cps, NameAsync
   * 4. Create forward TypeRef for function-storing classes (resolved later)
   *
   * Returns None if no delayed type found (will fallback to F[T]).
   */
  def findDelayedType(originalType: Type, ftp: Type)(using Context): Option[Type] = {
    val originalSym = originalType.widen.dealias.typeSymbol
    val originalName = originalSym.fullName.toString

    // Check 0: Auto-generated delayed class (from earlier in same compilation)
    if (originalSym.isClass) {
      DelayedClassGenerator.findAutoGeneratedDelayedClass(originalSym) match {
        case Some(delayedSym) if delayedSym != originalSym =>
          val delayedType = constructDelayedType(delayedSym.asClass, originalType, ftp)
          println(s"  Found auto-generated delayed type: ${delayedSym.name}")
          return Some(delayedType)
        case _ =>
          // Either not found or placeholder - continue to other checks
      }
    }

    // Check 1: Known mappings
    knownDelayedTypes.get(originalName) match {
      case Some(delayedName) =>
        val delayedSym = Symbols.getClassIfDefined(delayedName)
        if (delayedSym.exists) {
          val delayedType = constructDelayedType(delayedSym.asClass, originalType, ftp)
          println(s"  Found library delayed type: $delayedName")
          Some(delayedType)
        } else {
          // Delayed type not found in library (yet), continue to other checks
          println(s"  Library delayed type $delayedName not found (not implemented yet)")
          findDelayedTypeByNamingConvention(originalSym, originalType, ftp)
        }
      case None =>
        findDelayedTypeByNamingConvention(originalSym, originalType, ftp)
    }
  }

  /**
   * Find delayed type by naming conventions in same package.
   * If an existing type is found, use it. Otherwise, create a forward reference
   * to the expected delayed type name - it will be resolved later.
   */
  private def findDelayedTypeByNamingConvention(
      originalSym: Symbol,
      originalType: Type,
      ftp: Type
  )(using Context): Option[Type] = {
    val simpleOriginalName = originalSym.name.toString
    val candidates = Seq(
      "Delayed" + simpleOriginalName,
      simpleOriginalName + "_cps",
      simpleOriginalName + "Async"
    )

    // First, try to find an existing delayed type
    val existingType = candidates.iterator.flatMap { candidateName =>
      val candidateSym = originalSym.owner.info.member(candidateName.toTypeName).symbol
      if (candidateSym.exists && candidateSym.isClass) {
        val delayedType = constructDelayedType(candidateSym.asClass, originalType, ftp)
        println(s"  Found user-defined delayed type: ${candidateSym.fullName}")
        Some(delayedType)
      } else {
        None
      }
    }.nextOption()

    existingType.orElse {
      // No existing type found - create a forward reference to expected _cps type
      // This will be resolved later when the delayed class is generated
      if (originalSym.isClass && DelayedClassGenerator.isFunctionStoringClassSymbol(originalSym.asClass)) {
        val delayedName = (simpleOriginalName + "_cps").toTypeName
        val owner = originalSym.owner

        // Create forward TypeRef - will be resolved during later phases
        val forwardRef = TypeRef(owner.thisType, delayedName)

        // Apply type arguments: F_SHIFT, then original type args
        val originalArgs = originalType.widen.dealias match {
          case AppliedType(_, args) => args
          case _ => Nil
        }
        val delayedType = if (originalArgs.isEmpty) {
          forwardRef.appliedTo(ftp)
        } else {
          forwardRef.appliedTo(ftp :: originalArgs)
        }

        println(s"  Created forward reference to delayed type: ${owner.name}.$delayedName")
        Some(delayedType)
      } else {
        None
      }
    }
  }

  /**
   * Construct the delayed type with appropriate type arguments.
   *
   * For DelayedWithFilter[F[_], A, C[_], CA], we need to:
   * - Apply F_SHIFT as first type arg
   * - Extract and apply type args from original type
   */
  private def constructDelayedType(delayedClass: ClassSymbol, originalType: Type, ftp: Type)(using Context): Type = {
    // For now, simple case: just apply F and original type args
    // More sophisticated mapping may be needed for complex cases
    val originalArgs = originalType.widen.dealias match {
      case AppliedType(_, args) => args
      case _ => Nil
    }

    // Delayed type: DelayedXxx[F, originalArgs...]
    if (originalArgs.isEmpty) {
      delayedClass.typeRef.appliedTo(ftp)
    } else {
      delayedClass.typeRef.appliedTo(ftp :: originalArgs)
    }
  }

  /**
   * Get the shifted return type for call-chain methods.
   * Tries to find a delayed wrapper type, falls back to F[T] if not found.
   */
  def getCallChainReturnType(originalReturn: Type, ftp: Type)(using Context): Type = {
    findDelayedType(originalReturn, ftp) match {
      case Some(delayedType) =>
        delayedType
      case None =>
        // Fallback: standard F[T] wrapping
        // TODO: Consider auto-generating delayed class
        println(s"  No delayed type found, using F[${originalReturn.show}]")
        ftp.appliedTo(originalReturn.widen)
    }
  }

  /**
   * Create the shifted type signature for a method.
   * Adds F_SHIFT[_] type parameter to OUTER PolyType and monad parameter.
   * Preserves nested PolyType structure (e.g., extension [A](list) def myMap[B](f) keeps inner [B]).
   *
   * @param isCallChain If true, use delayed type for call-chain return types
   */
  def shiftedFunctionPolyType(f: DefDef, isCallChain: Boolean = false)(using Context): Type = {
    val fBound = TypeBounds(defn.NothingType, HKTypeLambda.any(1))

    f.tpe.widen match {
      case pt: PolyType =>
        // Method has type parameters - prepend F_SHIFT to outer PolyType only
        PolyType(paramNames = "F_SHIFT".toTypeName :: pt.paramNames.map(_.toTypeName))(
          npt => {
            // F_SHIFT bounds + original bounds (substituted with new refs)
            val newRefs = pt.paramInfos.indices.map(i => npt.newParamRef(i + 1)).toList
            val substitutedBounds = pt.paramInfos.map(_.substParams(pt, newRefs).asInstanceOf[TypeBounds])
            fBound :: substitutedBounds
          },
          npt => {
            // Transform the result type (MethodType or nested structure)
            val fShiftRef = npt.newParamRef(0)
            val newRefs = pt.paramInfos.indices.map(i => npt.newParamRef(i + 1)).toList

            // Add monad param and transform nested structure
            val transformed = MethodType(List("m".toTermName))(
              _ => List(Symbols.requiredClassRef("cps.CpsTryMonad").appliedTo(fShiftRef)),
              _ => transformNestedLambdaType(pt.resType, fShiftRef, isCallChain)
            )

            // Substitute old type param refs with new refs
            transformed.substParams(pt, newRefs)
          }
        )

      case mt: MethodType =>
        // Method has no type parameters - create PolyType with just F_SHIFT
        PolyType(List("F_SHIFT".toTypeName))(
          pt => List(fBound),
          pt => shiftedFunctionMethodType(mt, pt.newParamRef(0), isCallChain)
        )

      case other =>
        // Not a method type - shouldn't reach here
        other
    }
  }

  /**
   * Find the first MethodType in a type (after any outer PolyType).
   * For extensions: PolyType([A], MethodType(list, ...)) -> MethodType(list, ...)
   */
  private def findFirstMethodType(tp: Type)(using Context): MethodType = {
    tp match {
      case pt: PolyType => findFirstMethodType(pt.resultType)
      case mt: MethodType => mt
      case _ =>
        throw new RuntimeException(s"Expected MethodType, got ${tp.getClass}")
    }
  }

  /**
   * Transform method type: add monad parameter and shift function params/return type.
   * Handles curried methods (like extensions) by preserving nested MethodType/PolyType structure.
   *
   * @param isCallChain If true, use delayed type for call-chain return types
   */
  def shiftedFunctionMethodType(mt: MethodType, ftp: Type, isCallChain: Boolean = false)(using Context): Type = {
    // Add monad parameter as first parameter list, then transform all nested types
    MethodType(List("m".toTermName))(
      _ => List(Symbols.requiredClassRef("cps.CpsTryMonad").appliedTo(ftp)),
      _ => transformNestedLambdaType(mt, ftp, isCallChain)
    )
  }

  /**
   * Transform nested LambdaTypes (MethodType or PolyType), preserving the full nesting structure.
   * For extensions: `MethodType([list], PolyType([B], MethodType([f], result)))`
   * becomes: `MethodType([list], PolyType([B], MethodType([f'], F[result])))`
   * where function params are shifted and return type is wrapped.
   */
  private def transformNestedLambdaType(tp: Type, ftp: Type, isCallChain: Boolean)(using Context): Type = {
    tp match {
      case mt: MethodType =>
        val (shiftedParams, _) = shiftParamTypes(mt.paramInfos, ftp)
        mt.derivedLambdaType(
          mt.paramNames,
          shiftedParams,
          transformNestedLambdaType(mt.resType, ftp, isCallChain)
        )
      case pt: PolyType =>
        // Preserve the PolyType structure
        PolyType(pt.paramNames)(
          _ => pt.paramInfos,
          npt => {
            // Substitute old type param refs with new ones
            val newRefs = pt.paramInfos.indices.map(i => npt.newParamRef(i)).toList
            transformNestedLambdaType(pt.resType, ftp, isCallChain).substParams(pt, newRefs)
          }
        )
      case _ =>
        // Terminal type - transform the return type
        shiftedFunctionReturnType(tp, ftp, isCallChain)
    }
  }

  /**
   * Transform return type: wrap in F[_], handling nested method/function types.
   *
   * @param tp The return type to transform
   * @param ftp The monad type parameter (F_SHIFT)
   * @param isCallChain If true, use delayed type lookup for call-chain intermediates
   */
  def shiftedFunctionReturnType(tp: Type, ftp: Type, isCallChain: Boolean = false)(using Context): Type = {
    tp.widen.dealias match
      case pt: PolyType =>
        pt.derivedLambdaType(resType = shiftedFunctionReturnType(pt.resultType, ftp, isCallChain))
      case mtp: MethodType =>
        val (shiftedParams, _) = shiftParamTypes(mtp.paramInfos, ftp)
        mtp.derivedLambdaType(
          paramNames = mtp.paramNames,
          paramInfos = shiftedParams,
          resType = shiftedFunctionReturnType(mtp.resType, ftp, isCallChain)
        )
      case tp @ AppliedType(base, targs) if defn.isFunctionType(tp) || defn.isContextFunctionType(tp) =>
        // Nested function type - transform result type
        val params = targs.dropRight(1)
        val resType = targs.last
        val (shiftedParams, _) = shiftParamTypes(params, ftp)
        val nReturnType = shiftedFunctionReturnType(resType, ftp, isCallChain)
        defn.FunctionType(shiftedParams.length).appliedTo(shiftedParams :+ nReturnType)
      case _ =>
        // Check if this is a call-chain intermediate type that needs special handling
        if (isCallChain && isCallChainIntermediateType(tp)) {
          getCallChainReturnType(tp, ftp)
        } else {
          // Simple return type - wrap in F[_]
          CpsTransformHelper.cpsTransformedType(tp, ftp)
        }
  }

  /**
   * Transform parameter types: impure function params become async (A => B) -> (A => F[B])
   * Pure function params are left unchanged.
   */
  def shiftParamTypes(paramTypes: List[Type], ftp: Type)(using Context): (List[Type], Int) = {
    var count = 0
    val shifted = paramTypes.map { tp =>
      if (isImpureFunction(tp)) {
        count += 1
        CpsTransformHelper.cpsTransformedType(tp, ftp)
      } else {
        tp
      }
    }
    (shifted, count)
  }

  /**
   * Check if a type is an IMPURE function type (can capture capabilities).
   * Pure functions (A -> B) cannot capture and don't need shifting.
   * Impure functions (A => B) can capture the CPS capability.
   *
   * When capture checking is enabled:
   * - Only ImpureFunction and ContextFunction types can capture
   * - ImpureFunctionN / ImpureContextFunctionN are type aliases with capture sets
   *
   * When capture checking is NOT enabled:
   * - All function types are considered potentially impure (A => B = Function1)
   */
  def isImpureFunction(tp: Type)(using Context): Boolean = {
    if (Feature.ccEnabled) {
      // Check if it's an ImpureFunction type alias (like ImpureFunction1, ImpureContextFunction1)
      // Must check BEFORE widen/dealias which strips the type alias
      val typeSymbol = tp.typeSymbol
      if (typeSymbol.name.isImpureFunction) {
        return true
      }

      // Also check the dealiased type for context functions
      tp.widen.dealias match
        case AppliedType(tycon, _) =>
          val sym = tycon.typeSymbol
          // Context functions are always impure (can capture)
          defn.isContextFunctionType(tp)
        case _: MethodType => true  // Method types are inherently impure
        case _: PolyType => true    // Poly types wrapping methods
        case _ => false
    } else {
      // Capture checking is NOT enabled - treat all functions as potentially impure
      isAnyFunction(tp)
    }
  }

  /**
   * Check if a type is any kind of function (pure or impure).
   */
  def isAnyFunction(tp: Type)(using Context): Boolean = {
    tp.widen.dealias match
      case _: AppliedType if defn.isFunctionType(tp) || defn.isContextFunctionType(tp) => true
      case _: MethodType => true
      case _: PolyType => true
      case _ => false
  }

  /**
   * Check if a DefDef has function parameters that could be impure
   * (and thus might capture the CPS capability).
   */
  def hasImpureFunctionParams(tree: DefDef)(using Context): Boolean = {
    tree.paramss.flatten.exists {
      case vd: ValDef => CpsTypeHelper.isImpureFunction(vd.tpt.tpe)
      case _ => false
    }
  }

  // =========================================================================
  // Body generation helpers
  // =========================================================================

  /**
   * Select function-typed value parameters from param lists.
   */
  private def selectFunctionParams(paramss: List[ParamClause])(using Context): List[ValDef] = {
    paramss.flatMap { clause =>
      clause.collect {
        case vd: ValDef if isAnyFunction(vd.tpt.tpe) => vd
      }
    }
  }

  /**
   * Result of extracting shifted params - includes type subst info
   */
  case class ShiftedParamsInfo(
    fShiftType: Type,
    monadRef: Tree,
    valueParamMap: Map[Symbol, Tree],
    typeParamSubsts: List[(PolyType, List[Type])],   // (oldPolyTypes, newParamRefs) for nested PolyTypes
    termParamSubsts: List[(MethodType, List[Type])],  // (oldMethodTypes, newParamTypes) for curried methods
    typeSymbolSubst: Map[Symbol, Type]                // old type param symbol -> new type
  )

  /**
   * Build ShiftedParamsInfo from the original DefDef and new paramss.
   *
   * New paramss structure:
   * - [0]: type params (F_SHIFT, ...)  - may include inner PolyType params for extensions
   * - [1]: monad param (m: CpsTryMonad[F_SHIFT])
   * - [2...]: value params (may include inner TypeDefs for nested PolyTypes)
   */
  private def buildShiftedParamsInfo(
    md: DefDef,
    newParamss: List[List[Tree]]
  )(using Context): ShiftedParamsInfo = {
    // Extract F_SHIFT type and monad ref
    val fShiftType = newParamss.head.head.tpe
    val monadRef = newParamss(1).head

    println(s"DEBUG buildShiftedParamsInfo: newParamss.length = ${newParamss.length}")
    println(s"DEBUG buildShiftedParamsInfo: newParamss = ${newParamss.map(ps => ps.map(p => s"${p.getClass.getSimpleName}(${p.show})"))}")
    println(s"DEBUG buildShiftedParamsInfo: newParamss.drop(2) = ${newParamss.drop(2)}")

    // Build value param map: old symbol -> new tree
    // newParamss contains Ident refs (not ValDefs), skip TypeTrees
    val oldValueParams = md.paramss.flatMap(_.collect { case vd: ValDef => vd })
    val newValueParams = newParamss.drop(2).flatMap(_.filterNot(_.isInstanceOf[TypeTree]))

    println(s"DEBUG buildShiftedParamsInfo: oldValueParams = ${oldValueParams.map(_.name)}")
    println(s"DEBUG buildShiftedParamsInfo: newValueParams = ${newValueParams.map(_.show)}")

    val paramMap = oldValueParams.zip(newValueParams).map { case (old, neu) =>
      old.symbol -> (neu: Tree)
    }.toMap

    println(s"DEBUG buildShiftedParamsInfo: paramMap = ${paramMap.map { case (k, v) => s"${k.name} -> ${v.show}" }}")

    // Build type param substitutions for ALL PolyTypes (outer and inner)
    // For extensions: outer [A] and inner [B] both need substitution
    // Collect ALL type params from newParamss (except F_SHIFT which is first)
    val allPolyTypes = extractAllPolyTypes(md.tpe.widen)
    val allNewTypeRefs = newParamss.flatMap(_.collect { case tt: TypeTree => tt.tpe }).drop(1)  // Skip F_SHIFT

    println(s"DEBUG buildShiftedParamsInfo: allPolyTypes = ${allPolyTypes.map(pt => pt.paramNames.mkString("[", ",", "]"))}")
    println(s"DEBUG buildShiftedParamsInfo: allNewTypeRefs = ${allNewTypeRefs.map(_.show)}")

    val typeParamSubsts: List[(PolyType, List[Type])] = {
      var remaining = allNewTypeRefs
      allPolyTypes.flatMap { pt =>
        val needed = pt.paramNames.length
        if (remaining.length >= needed) {
          val (taken, rest) = remaining.splitAt(needed)
          remaining = rest
          println(s"DEBUG: typeParamSubst: ${pt.paramNames} -> ${taken.map(_.show)}")
          Some((pt, taken))
        } else {
          println(s"DEBUG: NOT ENOUGH type refs for ${pt.paramNames}, remaining = ${remaining.length}")
          None
        }
      }
    }
    println(s"DEBUG buildShiftedParamsInfo: typeParamSubsts count = ${typeParamSubsts.length}")

    // Build type symbol substitution (old type param symbol -> new type)
    val oldTypeParams = md.paramss.flatMap(_.collect { case td: TypeDef => td })
    val newTypeRefs = allNewTypeRefs
    val typeSymbolSubst = oldTypeParams.zip(newTypeRefs).map { case (old, neu) =>
      old.symbol -> neu
    }.toMap
    println(s"DEBUG buildShiftedParamsInfo: typeSymbolSubst = ${typeSymbolSubst.map { case (k, v) => s"${k.name} -> ${v.show}" }}")

    // Build term param substitutions for capture sets
    val allMethodTypes = extractAllMethodTypes(md.tpe.widen)
    val termParamSubsts: List[(MethodType, List[Type])] = {
      var remaining = newValueParams.map(_.tpe)
      allMethodTypes.flatMap { mt =>
        val needed = mt.paramInfos.length
        if (remaining.length >= needed) {
          val (taken, rest) = remaining.splitAt(needed)
          remaining = rest
          Some((mt, taken))
        } else None
      }
    }

    ShiftedParamsInfo(fShiftType, monadRef, paramMap, typeParamSubsts, termParamSubsts, typeSymbolSubst)
  }

  /**
   * Extract all nested MethodTypes from a type (handles curried methods).
   */
  private def extractAllMethodTypes(tp: Type)(using Context): List[MethodType] = {
    tp match {
      case pt: PolyType => extractAllMethodTypes(pt.resultType)
      case mt: MethodType => mt :: extractAllMethodTypes(mt.resultType)
      case _ => Nil
    }
  }

  /**
   * Extract all nested PolyTypes from a type (handles extension methods with nested PolyTypes).
   */
  private def extractAllPolyTypes(tp: Type)(using Context): List[PolyType] = {
    tp match {
      case pt: PolyType => pt :: extractAllPolyTypes(pt.resultType)
      case mt: MethodType => extractAllPolyTypes(mt.resultType)
      case _ => Nil
    }
  }

  /**
   * Transform body: substitute params and insert await for function calls.
   */
  private def transformBodyWithAwait(
    body: Tree,
    functionParams: List[ValDef],
    paramMap: Map[Symbol, Tree],
    fShiftType: Type,
    paramsInfo: ShiftedParamsInfo
  )(using Context): Tree = {
    val functionParamSyms = functionParams.map(_.symbol).toSet

    println(s"DEBUG transformBodyWithAwait: body = ${body.show}")
    println(s"DEBUG functionParamSyms = ${functionParamSyms.map(_.name)}")
    println(s"DEBUG paramMap keys = ${paramMap.keys.map(_.name)}")

    val mapper = new TreeMap {
      override def transform(tree: Tree)(using Context): Tree = {
        println(s"DEBUG transform: ${tree.getClass.getSimpleName} = ${tree.show}")

        tree match {
          // Lambda: Block(List(DefDef), Closure)
          // Recreate the lambda with substituted types using TreeTypeMap
          case block @ Block((ddef: DefDef) :: Nil, closure: Closure) =>
            println(s"DEBUG: Transforming lambda: ${ddef.name}")
            // Get the lambda's method type and substitute types
            val oldMt = ddef.tpe.widen.asInstanceOf[MethodType]
            val newParamTypes = oldMt.paramInfos.map(t => substituteAllParams(t, paramsInfo))
            val newResultType = substituteAllParams(oldMt.resultType, paramsInfo)
            val newMt = MethodType(oldMt.paramNames)(_ => newParamTypes, _ => newResultType)

            // Create new method symbol with substituted types
            val owner = summon[Context].owner
            val newMethSym = Symbols.newAnonFun(owner, newMt)

            // Build new lambda using Closure, which will handle body transformation
            Closure(
              newMethSym,
              { tss =>
                // Build symbol and type substitution maps
                val oldParams = ddef.paramss.flatten.collect { case vd: ValDef => vd }
                val newParams = tss.head

                // Create substitution maps for TreeTypeMap
                val oldSymbols = oldParams.map(_.symbol)
                val newSymbols = newParams.map(_.symbol)
                val (oldTypeSyms, newTypes) = paramsInfo.typeSymbolSubst.toList.unzip

                // Use TreeTypeMap to properly substitute symbols in the entire tree
                val treeMap = new TreeTypeMap(
                  oldOwners = List(ddef.symbol),
                  newOwners = List(newMethSym),
                  substFrom = oldSymbols ++ oldTypeSyms,
                  substTo = newSymbols ++ newTypes.map(_.typeSymbol)
                )

                // First apply type substitution, then tree mapping
                val bodyCtx = summon[Context].withOwner(newMethSym)
                val transformedBody = transform(ddef.rhs)(using bodyCtx)
                treeMap.transform(transformedBody)
              }
            )

          // f(args) or f.apply(args) or f(a)(b) where f is a function param
          case app: Apply =>
            extractFunctionParamFromCall(app, functionParamSyms) match {
              case Some(funcParamIdent) =>
                println(s"DEBUG: MATCHED function param call: ${funcParamIdent.show} in ${app.show}")
                // Transform the application, but substitute the function param WITHOUT wrapping
                // (we'll insert await around the whole call, not wrap the function)
                val transformedApp = transformAppWithFuncParamSubst(app, funcParamIdent.symbol, paramMap, paramsInfo)
                println(s"DEBUG: transformedApp = ${transformedApp.show}, type = ${transformedApp.tpe.show}")
                val result = insertAwait(transformedApp, fShiftType)
                println(s"DEBUG: after insertAwait = ${result.show}, type = ${result.tpe.show}")
                result
              case None =>
                super.transform(tree)
            }

          // Substitute param references
          case id: Ident if paramMap.contains(id.symbol) =>
            val newParam = paramMap(id.symbol)
            // Check if this is a function param being used (not called)
            if (functionParamSyms.contains(id.symbol)) {
              // Create wrapper lambda: x => scaffoldAwait(f(x))
              println(s"DEBUG: WRAPPING function param ${id.name} with await lambda")
              println(s"DEBUG:   oldType = ${id.tpe.show}, newParam = ${newParam.show}, newParamType = ${newParam.tpe.show}")
              wrapFunctionWithAwait(newParam, id.tpe, fShiftType, paramsInfo)
            } else {
              println(s"DEBUG: SUBSTITUTING param ${id.name} -> ${newParam.show}")
              newParam
            }

          // Transform type trees to substitute type params
          case tt: TypeTree =>
            val newType = substituteAllParams(tt.tpe, paramsInfo)
            if (newType ne tt.tpe) {
              println(s"DEBUG: TRANSFORMING TypeTree ${tt.tpe.show} -> ${newType.show}")
              TypeTree(newType)
            } else {
              tt
            }

          case _ =>
            super.transform(tree)
        }
      }
    }

    val result = mapper.transform(body)
    println(s"DEBUG transformBodyWithAwait result: ${result.show}")
    result
  }

  /**
   * Transform an application that calls a function param, substituting params but NOT wrapping.
   * This prevents double-transformation when we're already inserting await around the call.
   */
  private def transformAppWithFuncParamSubst(
    app: Apply,
    funcParamSym: Symbol,
    paramMap: Map[Symbol, Tree],
    paramsInfo: ShiftedParamsInfo
  )(using Context): Tree = {
    // Create a specialized TreeMap that substitutes the function param directly (no wrapping)
    val mapper = new TreeMap {
      override def transform(tree: Tree)(using Context): Tree = {
        tree match {
          case id: Ident if id.symbol == funcParamSym =>
            // Substitute the function param WITHOUT wrapping
            paramMap.get(id.symbol) match {
              case Some(newParam) =>
                println(s"DEBUG: DIRECT SUBST func param ${id.name} -> ${newParam.show}")
                newParam
              case None => id
            }
          case id: Ident if paramMap.contains(id.symbol) =>
            // Substitute other params normally
            paramMap(id.symbol)
          case tt: TypeTree =>
            val newType = substituteAllParams(tt.tpe, paramsInfo)
            if (newType ne tt.tpe) TypeTree(newType) else tt
          case _ =>
            super.transform(tree)
        }
      }
    }
    mapper.transform(app)
  }

  /**
   * Extract function param from a call expression.
   * Handles:
   *   - `f(x)` -> returns f
   *   - `f.apply(x)` -> returns f
   *   - `f(a)(b)` (curried) -> returns f
   *   - `f.apply(a).apply(b)` -> returns f
   *
   * Returns Some(functionParamIdent) if this is a call to a function param, None otherwise.
   */
  private def extractFunctionParamFromCall(fun: Tree, functionParamSyms: Set[Symbol])(using Context): Option[Tree] = {
    fun match {
      // Direct: f
      case id: Ident if functionParamSyms.contains(id.symbol) =>
        Some(id)

      // f.apply
      case Select(qual, name) if name == nme.apply =>
        extractFunctionParamFromCall(qual, functionParamSyms)

      // Curried: f(a) in f(a)(b) - the inner Apply's result is the function for outer Apply
      case Apply(inner, _) =>
        extractFunctionParamFromCall(inner, functionParamSyms)

      // TypeApply: f[T](a)
      case TypeApply(inner, _) =>
        extractFunctionParamFromCall(inner, functionParamSyms)

      case _ => None
    }
  }

  /**
   * Insert await around a tree: tree -> scaffoldAwait[F, T](tree)
   * tree has type F[T], result has type T
   */
  private def insertAwait(tree: Tree, fShiftType: Type)(using Context): Tree = {
    // Extract T from F[T]
    val treeTpe = tree.tpe.widen
    val innerType = treeTpe match {
      case AppliedType(_, args) if args.nonEmpty => args.last
      case _ => treeTpe // fallback
    }

    val awaitMethod = ref(Symbols.requiredMethod("cps.plugin.scaffoldAwait"))
    Apply(
      TypeApply(awaitMethod, List(TypeTree(fShiftType), TypeTree(innerType))),
      List(tree)
    )
  }

  /**
   * Wrap a function param with await lambda: f -> (x => scaffoldAwait(f(x)))
   *
   * @param newFuncRef the new function param reference (type A => F[B])
   * @param originalFuncType the original function type (A => B)
   * @param fShiftType the monad type F
   * @param paramsInfo contains type substitution info
   * @return a lambda with original type (A => B) that awaits the new function
   */
  private def wrapFunctionWithAwait(newFuncRef: Tree, originalFuncType: Type, fShiftType: Type, paramsInfo: ShiftedParamsInfo)(using Context): Tree = {
    val funcType = originalFuncType.widen.dealias

    // Check if it's a function type and extract param/result types
    val funcTypeInfo: Option[(List[Type], Type)] = funcType match {
      case AppliedType(tycon, targs) if defn.isFunctionType(funcType) || defn.isContextFunctionType(funcType) =>
        Some((targs.dropRight(1), targs.last))
      case _ =>
        None
    }

    funcTypeInfo match {
      case Some((paramTypes, resultType)) =>
        // Substitute type params in the lambda's param and result types
        val substParamTypes = paramTypes.map(pt => substituteAllParams(pt, paramsInfo))
        val substResultType = substituteAllParams(resultType, paramsInfo)

        // Create lambda parameters
        val paramNames = substParamTypes.zipWithIndex.map { (_, i) => s"x$i".toTermName }

        // Build method type for the lambda
        val mt = MethodType(paramNames)(_ => substParamTypes, _ => substResultType)

        // Create anonymous function symbol
        val owner = summon[Context].owner
        val methSym = Symbols.newAnonFun(owner, mt)

        println(s"DEBUG: Creating lambda wrapper for ${originalFuncType.show}")
        println(s"DEBUG:   original paramTypes = ${paramTypes.map(t => s"${t.show}:${t.getClass.getSimpleName}")}")
        println(s"DEBUG:   subst paramTypes = ${substParamTypes.map(t => s"${t.show}:${t.getClass.getSimpleName}")}")

        // Build lambda: (x0, x1, ...) => scaffoldAwait(f(x0, x1, ...))
        Closure(
          methSym,
          { tss =>
            val paramRefs = tss.head  // First param list
            // Apply function to params: f.apply(x0, x1, ...)
            // Use Select(f, apply) because just Apply(f, args) doesn't work for function values
            val funcApply = Select(newFuncRef, nme.apply)
            val funcApp = Apply(funcApply, paramRefs)
            // Wrap with await: scaffoldAwait(f.apply(x0, x1, ...))
            insertAwait(funcApp, fShiftType)
          }
        )

      case None =>
        // Not a function type - shouldn't happen, but fallback to original
        println(s"WARNING: wrapFunctionWithAwait called on non-function type: ${originalFuncType.show}, widen.dealias = ${funcType.show}, class = ${funcType.getClass}")
        newFuncRef
    }
  }

  /**
   * Generate cpsPhase1Scaffold[F, T, R](m, body) call.
   */
  def generateScaffoldCall(
    fShiftType: Type,
    originalReturnType: Type,
    cpsReturnType: Type,
    monadRef: Tree,
    body: Tree
  )(using Context): Tree = {
    // Cast body to expected type to handle capture checking mismatches
    // The transformed body may have different capture annotations than the original
    // (e.g., captures from delayed class instead of original class)
    val bodyType = originalReturnType.widen
    val castBody = body.asInstance(bodyType)

    val scaffoldMethod = ref(Symbols.requiredMethod("cps.plugin.cpsPhase1Scaffold"))
    Apply(
      TypeApply(
        scaffoldMethod,
        List(
          TypeTree(fShiftType),
          TypeTree(bodyType),
          TypeTree(cpsReturnType)
        )
      ),
      List(monadRef, castBody)
    )
  }

  /**
   * Build ShiftedParamsInfo for a delayed class method.
   * Similar to buildShiftedParamsInfo but works with the delayed class context.
   */
  def buildShiftedParamsInfoForDelayed(
    origMethod: DefDef,
    asyncParamss: List[List[Tree]],
    fShiftType: Type,
    monadRef: Tree
  )(using Context): ShiftedParamsInfo = {
    // asyncParamss structure for delayed class methods:
    // - [0]: type params (method's own type params like [C])
    // - [1...]: value params (g: B => F[C], etc.)

    // Build value param map: old symbol -> new tree
    val oldValueParams = origMethod.paramss.flatMap(_.collect { case vd: ValDef => vd })
    val newValueParams = asyncParamss.flatMap(_.filterNot(_.isInstanceOf[TypeTree]))

    val paramMap = oldValueParams.zip(newValueParams).map { case (old, neu) =>
      old.symbol -> (neu: Tree)
    }.toMap

    // Build type param substitutions for method's PolyTypes
    val allPolyTypes = extractAllPolyTypes(origMethod.tpe.widen)
    val allNewTypeRefs = asyncParamss.flatMap(_.collect { case tt: TypeTree => tt.tpe })

    val typeParamSubsts: List[(PolyType, List[Type])] = {
      var remaining = allNewTypeRefs
      allPolyTypes.flatMap { pt =>
        val needed = pt.paramNames.length
        if (remaining.length >= needed) {
          val (taken, rest) = remaining.splitAt(needed)
          remaining = rest
          Some((pt, taken))
        } else None
      }
    }

    // Build type symbol substitution (old type param symbol -> new type)
    val oldTypeParams = origMethod.paramss.flatMap(_.collect { case td: TypeDef => td })
    val typeSymbolSubst = oldTypeParams.zip(allNewTypeRefs).map { case (old, neu) =>
      old.symbol -> neu
    }.toMap

    // Build term param substitutions
    val allMethodTypes = extractAllMethodTypes(origMethod.tpe.widen)
    val termParamSubsts: List[(MethodType, List[Type])] = {
      var remaining = newValueParams.map(_.tpe)
      allMethodTypes.flatMap { mt =>
        val needed = mt.paramInfos.length
        if (remaining.length >= needed) {
          val (taken, rest) = remaining.splitAt(needed)
          remaining = rest
          Some((mt, taken))
        } else None
      }
    }

    ShiftedParamsInfo(fShiftType, monadRef, paramMap, typeParamSubsts, termParamSubsts, typeSymbolSubst)
  }

  /**
   * Transform a method body for a delayed class method, handling class field access.
   * Combines ShiftedMethodGenerator's transformBodyWithAwait with delayed class field handling.
   *
   * Uses TreeTypeMap instead of TreeMap to properly remap symbol ownership for nested lambdas.
   */
  def transformBodyForDelayed(
    body: Tree,
    delayedClass: ClassSymbol,
    originalClass: ClassSymbol,
    functionFieldSyms: Set[Symbol],
    functionParams: List[ValDef],
    paramsInfo: ShiftedParamsInfo,
    fShiftType: Type,
    origMethodSym: Symbol,
    newMethodSym: Symbol
  )(using Context): Tree = {
    val functionParamSyms = functionParams.map(_.symbol).toSet
    val functionFieldNames = functionFieldSyms.map(_.name)

    // Build TreeTypeMap for symbol ownership remapping
    // This ensures lambdas and nested symbols get proper owners in new context
    val (oldTypeSyms, newTypes) = paramsInfo.typeSymbolSubst.toList.unzip
    val (oldParamSyms, newParamTrees) = paramsInfo.valueParamMap.toList.unzip

    // Skip TreeTypeMap for now - just use the body directly
    // The type substitution should be handled by our TreeMap below
    val ownerMappedBody = body

    // Second pass: apply our semantic transformations
    val mapper = new TreeMap {
      override def transform(tree: Tree)(using Context): Tree = {
        tree match {
          // Ident(fieldName) where fieldName is owned by originalClass -> This(delayedClass).fieldName
          // NOTE: In Scala 3 tree representation, `OriginalClass.this.field` is often represented
          // as `Ident(field)` where field.owner == OriginalClass. The .show displays it as
          // "OriginalClass.this.field" but the actual tree is just an Ident.
          //
          // When a function field is used as a VALUE (not called), we need to wrap it with
          // an await lambda: f -> (x => await(f(x))). This handles cases like source.map(f).
          case id: Ident if id.symbol.exists && id.symbol.owner == originalClass && id.symbol.is(ParamAccessor) =>
            val delayedFieldSym = delayedClass.info.member(id.name).symbol
            if (delayedFieldSym.exists) {
              val delayedRef = This(delayedClass).select(delayedFieldSym)
              // If this is a function field being used as a VALUE, wrap with await lambda
              // Use id.tpe (the original sync type) and delayedRef (the new async reference)
              if (functionFieldNames.contains(id.name)) {
                wrapFunctionWithAwait(delayedRef, id.tpe, fShiftType, paramsInfo)
              } else {
                delayedRef
              }
            } else {
              super.transform(tree)
            }

          // this.field reference where field is in original class (explicit Select form)
          case sel @ Select(ths: This, name) if ths.symbol == originalClass =>
            // Reference to original class field -> reference delayed class field
            val delayedFieldSym = delayedClass.info.member(name).symbol
            if (delayedFieldSym.exists) {
              val delayedRef = This(delayedClass).select(delayedFieldSym)
              // DON'T wrap function fields here - only wrap when they're CALLED
              delayedRef
            } else {
              super.transform(tree)
            }

          // Param reference -> substitute with new param
          case id: Ident if paramsInfo.valueParamMap.contains(id.symbol) =>
            paramsInfo.valueParamMap(id.symbol)

          // Function call: f(x) where f is a function field or param
          case app @ Apply(fun, args) =>
            // Check if this is a call to a function field or function param
            val isAsyncCall = extractFunctionFromCallForDelayed(fun, originalClass, delayedClass,
              functionParamSyms, functionFieldNames)
            println(s"DEBUG transformBodyForDelayed Apply: fun=${fun.show}, isAsyncCall=$isAsyncCall, functionFieldNames=$functionFieldNames")

            if (isAsyncCall) {
              // For async calls, transform this references to delayed class, but DON'T
              // wrap the function itself with await lambda (it's being called, not passed)
              // Transform arguments (may contain nested async calls like f(x) in g(f(x)))
              val transformedFun = transformFunForDelayed(fun, originalClass, delayedClass)
              val transformedArgs = args.map(transform)
              // Reconstruct the Apply with transformed parts
              val transformedApp = cpy.Apply(app)(transformedFun, transformedArgs)
              println(s"DEBUG: transformedApp = ${transformedApp.show}, type = ${transformedApp.tpe.show}")
              // Then wrap with await
              val result = insertAwait(transformedApp, fShiftType)
              println(s"DEBUG: after insertAwait = ${result.show}, type = ${result.tpe.show}")
              result
            } else {
              super.transform(tree)
            }

          case tt: TypeTree =>
            val newType = substituteAllParams(tt.tpe, paramsInfo)
            if (newType ne tt.tpe) TypeTree(newType) else tt

          // Lambda: Block(List(DefDef), Closure)
          // Recreate the lambda with substituted types using TreeTypeMap (same as transformBodyWithAwait)
          case block @ Block((ddef: DefDef) :: Nil, closure: Closure) =>
            println(s"DEBUG transformBodyForDelayed: Transforming lambda: ${ddef.name}")
            // Get the lambda's method type and substitute types
            val oldMt = ddef.tpe.widen.asInstanceOf[MethodType]
            val newParamTypes = oldMt.paramInfos.map(t => substituteAllParams(t, paramsInfo))
            val newResultType = substituteAllParams(oldMt.resultType, paramsInfo)
            val newMt = MethodType(oldMt.paramNames)(_ => newParamTypes, _ => newResultType)

            // Create new method symbol with substituted types
            val owner = summon[Context].owner
            val newMethSym = Symbols.newAnonFun(owner, newMt)

            // Build new lambda using Closure, which will handle body transformation
            Closure(
              newMethSym,
              { tss =>
                // Build symbol and type substitution maps
                val oldParams = ddef.paramss.flatten.collect { case vd: ValDef => vd }
                val newParams = tss.head

                // Create substitution maps for TreeTypeMap
                val oldLambdaSymbols = oldParams.map(_.symbol)
                val newLambdaSymbols = newParams.map(_.symbol)
                val (oldTypeSyms, newTypes) = paramsInfo.typeSymbolSubst.toList.unzip

                // Also include method param symbols from paramsInfo.valueParamMap
                val (oldValueParamSyms, newValueParamTrees) = paramsInfo.valueParamMap.toList.unzip
                val newValueParamSyms = newValueParamTrees.map(_.symbol)

                // Use TreeTypeMap to properly substitute symbols in the entire tree
                val treeMap = new TreeTypeMap(
                  oldOwners = List(ddef.symbol),
                  newOwners = List(newMethSym),
                  substFrom = oldLambdaSymbols ++ oldTypeSyms ++ oldValueParamSyms,
                  substTo = newLambdaSymbols ++ newTypes.map(_.typeSymbol) ++ newValueParamSyms
                )

                // First transform the body recursively, then apply type/symbol mapping
                val bodyCtx = summon[Context].withOwner(newMethSym)
                val transformedBody = transform(ddef.rhs)(using bodyCtx)
                treeMap.transform(transformedBody)
              }
            )

          case _ =>
            super.transform(tree)
        }
      }
    }

    mapper.transform(ownerMappedBody)
  }

  /**
   * Transform a function tree for delayed class context.
   * Replaces `this.f` with `delayed_this.f`, handling nested structures like `this.f.apply`.
   *
   * NOTE: In Scala tree representation, `LazyA.this.f` is often represented as `Ident(f)`
   * where f is a field symbol owned by LazyA. The `.show` method displays it as `LazyA.this.f`
   * but the actual tree node is just an Ident.
   */
  private def transformFunForDelayed(
    fun: Tree,
    originalClass: ClassSymbol,
    delayedClass: ClassSymbol
  )(using Context): Tree = {
    fun match {
      // this -> delayed_this (must be before Select cases)
      case ths: This if ths.symbol == originalClass =>
        println(s"DEBUG transformFunForDelayed: This(${ths.symbol}) -> This(${delayedClass})")
        This(delayedClass)

      // Ident(f) where f is a field of originalClass -> delayed_this.f
      // NOTE: LazyA.this.f is represented as Ident(f) in the tree, not Select(This, f)
      case id: Ident if id.symbol.owner == originalClass =>
        val delayedFieldSym = delayedClass.info.member(id.name).symbol
        println(s"DEBUG transformFunForDelayed: Ident(${id.name}) owned by original class -> delayed ref, delayedFieldSym.exists=${delayedFieldSym.exists}")
        if (delayedFieldSym.exists) {
          This(delayedClass).select(delayedFieldSym)
        } else {
          fun
        }

      // this.f -> delayed_this.f (specific Select case, must be before general Select)
      case sel @ Select(ths: This, name) if ths.symbol == originalClass =>
        println(s"DEBUG transformFunForDelayed: Select(This(${ths.symbol}), ${name}) -> delayed ref")
        val delayedFieldSym = delayedClass.info.member(name).symbol
        if (delayedFieldSym.exists) This(delayedClass).select(delayedFieldSym)
        else fun

      // General Select: recurse into inner (for cases like this.f.apply)
      case Select(inner, name) =>
        println(s"DEBUG transformFunForDelayed: Select(inner, ${name}), inner = ${inner.show}, inner.class = ${inner.getClass.getSimpleName}")
        val transformedInner = transformFunForDelayed(inner, originalClass, delayedClass)
        if (transformedInner eq inner) {
          println(s"DEBUG transformFunForDelayed: inner unchanged")
          fun
        } else {
          println(s"DEBUG transformFunForDelayed: inner transformed to ${transformedInner.show}")
          cpy.Select(fun)(transformedInner, name)
        }

      // TypeApply: f[T] -> transform f
      case TypeApply(inner, targs) =>
        val transformedInner = transformFunForDelayed(inner, originalClass, delayedClass)
        if (transformedInner eq inner) fun
        else cpy.TypeApply(fun)(transformedInner, targs)

      // Curried: f(a) in f(a)(b) - the inner Apply
      case Apply(inner, args) =>
        val transformedInner = transformFunForDelayed(inner, originalClass, delayedClass)
        if (transformedInner eq inner) fun
        else cpy.Apply(fun)(transformedInner, args)

      case _ =>
        println(s"DEBUG transformFunForDelayed: unmatched ${fun.show}, class = ${fun.getClass.getSimpleName}")
        fun
    }
  }

  /**
   * Check if a function call is to an async function (field or param) in a delayed class context.
   */
  private def extractFunctionFromCallForDelayed(
    fun: Tree,
    originalClass: ClassSymbol,
    delayedClass: ClassSymbol,
    functionParamSyms: Set[Symbol],
    functionFieldNames: Set[Name]
  )(using Context): Boolean = {
    fun match {
      // Direct: f (could be a param OR a field reference)
      case id: Ident =>
        // Check if it's a function param
        val isParam = functionParamSyms.contains(id.symbol)
        // Check if it's a field reference (symbol is owned by originalClass and is a function field)
        val isField = id.symbol.owner == originalClass && functionFieldNames.contains(id.name)
        println(s"DEBUG extractFunctionFromCallForDelayed: Ident(${id.name}), symbol=${id.symbol}, owner=${id.symbol.owner}, isParam=$isParam, isField=$isField")
        isParam || isField

      // this.f or this.f.apply
      case Select(qual, name) if name == nme.apply =>
        println(s"DEBUG extractFunctionFromCallForDelayed: matched .apply, recursing on ${qual.show}")
        extractFunctionFromCallForDelayed(qual, originalClass, delayedClass, functionParamSyms, functionFieldNames)

      case Select(ths: This, name) =>
        val isThisMatch = ths.symbol == originalClass || ths.symbol == delayedClass
        val isFieldMatch = functionFieldNames.contains(name)
        println(s"DEBUG extractFunctionFromCallForDelayed: Select(This(${ths.symbol}), ${name}), originalClass=${originalClass}, delayedClass=${delayedClass}, isThisMatch=$isThisMatch, isFieldMatch=$isFieldMatch")
        isThisMatch && isFieldMatch

      case Select(inner, name) =>
        println(s"DEBUG extractFunctionFromCallForDelayed: generic Select($name), inner.show=${inner.show}, inner.class=${inner.getClass.getSimpleName}")
        inner match {
          case th: This => println(s"  inner IS This, symbol=${th.symbol}")
          case id: Ident => println(s"  inner IS Ident, symbol=${id.symbol}")
          case sel: Select => println(s"  inner IS Select, qual.class=${sel.qualifier.getClass.getSimpleName}")
          case _ => println(s"  inner is other")
        }
        false

      // Curried or TypeApply
      case Apply(inner, _) =>
        extractFunctionFromCallForDelayed(inner, originalClass, delayedClass, functionParamSyms, functionFieldNames)

      case TypeApply(inner, _) =>
        extractFunctionFromCallForDelayed(inner, originalClass, delayedClass, functionParamSyms, functionFieldNames)

      case other =>
        println(s"DEBUG extractFunctionFromCallForDelayed: unmatched case ${other.show}, class=${other.getClass.getSimpleName}")
        false
    }
  }

}
