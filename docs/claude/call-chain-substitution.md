# Call Chain Substitution Analysis for CPS2

## Problem Statement

When transforming higher-order functions like `withFilter`, the standard shifted signature pattern:

```scala
// Original
def withFilter(p: A => Boolean): WithFilter[A, C]

// Standard shifted pattern
def withFilter_async[F[_]](m: CpsMonad[F])(p: A => F[Boolean]): F[WithFilter[A, C]]
```

Causes a **double iteration problem**:

```scala
for {
  url <- urls if await(status(url)) == Active
  items <- await(api.retrieveItems(url))
  item <- items
} yield item
```

This desugars to:
```scala
urls.withFilter(url => await(status(url)) == Active)
    .flatMap(url => ...)
```

With standard transformation:
1. First pass: `withFilter_async` evaluates predicate for all elements, returns `F[WithFilter]`
2. Second pass: After `flatMap` over `F[WithFilter]`, iterate again for `flatMap`

## Solution: CallChainAsyncShiftSubst

Instead of returning `F[T]`, return a **delayed wrapper** that:
1. Stores the async predicate without evaluating
2. Provides its own `map`, `flatMap`, `foreach` methods
3. Combines stored predicate with next operation in single pass

```scala
// Instead of F[WithFilter[A, C]]
def withFilter_async[F[_]](m: CpsMonad[F])(p: A => F[Boolean]): DelayedWithFilter[F, A, C, CA]

// DelayedWithFilter has:
class DelayedWithFilter[F[_], A, C[_], CA](c: CA, m: CpsMonad[F], p: A => F[Boolean])
    extends CallChainAsyncShiftSubst[F, WithFilter[A, C], F[WithFilter[A, C]]] {

  def _finishChain: F[WithFilter[A, C]] = // Called when chain ends prematurely

  def map[B](f: A => B): F[C[B]] = // Combine predicate + map in single pass
  def map_async[B](f: A => F[B]): F[C[B]] = // Async version

  def flatMap[B](f: A => IterableOnce[B]): F[C[B]]
  def flatMap_async[B](f: A => F[IterableOnce[B]]): F[C[B]]

  def withFilter(q: A => Boolean): DelayedWithFilter[F, A, C, CA] // Chain more filters
  def withFilter_async(q: A => F[Boolean]): DelayedWithFilter[F, A, C, CA]

  def foreach[U](f: A => U): F[Unit]
  def foreach_async[U](f: A => F[U]): F[Unit]
}
```

## Detection: When to use CallChainAsyncShiftSubst?

### Key Insight: Function Storage

The core characteristic is: **the returned object STORES the function argument**.

| Method | Function Argument | Stored? | Needs Chain Subst? |
|--------|------------------|---------|-------------------|
| `list.map(f)` | `f: A => B` | No, applied immediately | No |
| `list.filter(p)` | `p: A => Boolean` | No, applied immediately | No |
| `list.withFilter(p)` | `p: A => Boolean` | **YES, stored** | **YES** |
| `iterator.map(f)` | `f: A => B` | **YES, stored** | **YES** |
| `view.filter(p)` | `p: A => Boolean` | **YES, stored** | **YES** |

When a function is **stored** inside the returned object:
- The function will be called **later**, when the chain is "materialized"
- If the function is async (`A ->{cps} B`), we need a delayed wrapper
- The wrapper stores `A => F[Boolean]` instead of `A => Boolean`

### Characteristics of "Function-Storing" Types

1. **STORES function argument** - The returned object holds a reference to the function
2. **Lazy evaluation** - The function isn't called during the method call
3. **HAS continuation methods** - `map`, `flatMap`, `foreach` that will use the stored function

### How to Detect "Function Storage"?

#### Approach 1: Type Structure Analysis

If the return type **contains** the function parameter type, it's likely stored:

```scala
def withFilter(p: A => Boolean): WithFilter[A, C]
// WithFilter internally has: class WithFilter[A, C](p: A => Boolean, ...)
// The function type appears in the class - it's STORED
```

We could check:
```scala
def storesFunctionParam(methodType: MethodType, returnType: Type)(using Context): Boolean = {
  val functionParams = methodType.paramInfos.filter(isImpureFunction)
  functionParams.exists { funcType =>
    returnType.dealias match {
      case AppliedType(tycon, args) =>
        // Check if any constructor param or field has this function type
        val classSym = tycon.typeSymbol.asClass
        classSym.primaryConstructor.paramSymss.flatten.exists { param =>
          param.info <:< funcType || param.info.finalResultType <:< funcType.finalResultType
        }
      case _ => false
    }
  }
}
```

#### Approach 2: Semantic Analysis (More Reliable)

Check if the method body **doesn't apply** the function:
- If function `f` is called in the body → immediate application → no chain subst needed
- If function `f` is only passed to constructor/stored → lazy → chain subst needed

This requires analyzing the method body, which is more complex but more accurate.

#### Approach 3: Capture Set Flow Analysis (Preferred for cps2)

**Key Insight**: With capture checking, if a function's capabilities appear in the return type's capture set, the function is stored.

```scala
// Function stored → capabilities flow to result:
def withFilter(p: A ->{cps} Boolean): WithFilter[A, C]^{cps}
//                   ^^^^^                            ^^^^^
//                   captures cps                     ALSO captures cps!

// Function applied immediately → no flow:
def map(f: A ->{cps} B): List[B]
//          ^^^^^        ^^^^^^^
//          captures cps NO capture - f was applied, not stored
```

**Detection**: Check if return type would capture from function parameter:
```scala
def storesFunctionCapabilities(methodType: Type, returnType: Type)(using Context): Boolean = {
  // The return type's capture set includes function param's captures
  // iff the function is stored in the result

  // At PostTyper (before CC), we can check structurally:
  // Does return type's class have function-typed fields?
  val returnClass = returnType.typeSymbol.asClass
  val hasStoredFunction = returnClass.primaryConstructor.paramSymss.flatten.exists { param =>
    isImpureFunction(param.info)
  }

  // Or if CC has run, check capture sets directly:
  // returnType.captureSet.elems.intersect(functionParam.captureSet.elems).nonEmpty

  hasStoredFunction
}
```

**Why this works**: A class that stores a function will have that function's captures in its own capture set. This is exactly what capture checking computes.

#### Approach 4: Known Patterns + Annotation (Fallback)

For cases where structural analysis isn't sufficient:

```scala
// Known: WithFilter, Iterator ops, View ops
val knownLazyTypes = Set(
  "scala.collection.WithFilter",
  "scala.collection.Iterator",
  "scala.collection.View",
  // ...
)

// User can add via annotation
@storedFunction  // or @callChainSubst
def myLazyOp(f: A => B): MyLazyWrapper[A, B]
```

### Proposed Detection Algorithm

```scala
/**
 * Detect if a method returns a "call chain intermediate" type that stores
 * function arguments. Such methods need CallChainAsyncShiftSubst handling.
 *
 * Detection criteria:
 * 1. Return type's class has function-typed constructor parameters/fields
 * 2. OR return type is a known lazy type (WithFilter, Iterator, View)
 * 3. OR method is annotated with @storedFunction
 */
def needsCallChainSubstitution(methodType: MethodType, returnType: Type)(using Context): Boolean = {
  // Skip if no impure function parameters
  val hasFunctionParams = methodType.paramInfos.exists(isImpureFunction)
  if (!hasFunctionParams) return false

  // Check 1: Does return type store functions? (Capture flow detection)
  val returnClass = returnType.widen.dealias.typeSymbol
  if (returnClass.isClass) {
    val storesFunctions = returnClass.asClass.primaryConstructor.paramSymss.flatten.exists { param =>
      isAnyFunction(param.info)  // Constructor has function-typed parameter
    }
    if (storesFunctions) return true
  }

  // Check 2: Known lazy types
  val knownLazyTypes = Set(
    "scala.collection.WithFilter",
    "scala.collection.IterableOps.WithFilter",
    "scala.collection.Iterator",
    "scala.collection.View",
  )
  if (knownLazyTypes.contains(returnClass.fullName.toString)) return true

  // Check 3: Annotation (future)
  // if (methodSymbol.hasAnnotation(StoredFunctionAnnot)) return true

  false
}

def isCallChainIntermediateType(returnType: Type)(using Context): Boolean = {
  // 1. Skip if already F[_] wrapped
  if (returnType.derivesFrom(monadType.typeSymbol)) return false

  // 2. Check for known intermediate types
  val knownIntermediates = Set(
    "scala.collection.WithFilter",
    "scala.collection.IterableOps.WithFilter",
    // Add more as needed
  )
  if (knownIntermediates.contains(returnType.typeSymbol.fullName.toString)) return true

  // 3. Heuristic: Has continuation methods that "complete" the chain
  val mapMember = returnType.member("map".toTermName)
  val flatMapMember = returnType.member("flatMap".toTermName)

  if (!mapMember.exists || !flatMapMember.exists) return false

  // Check if map's return type is different from returnType (completes chain)
  val mapResultType = mapMember.info.finalResultType
  val completesChain = !(mapResultType.typeSymbol == returnType.typeSymbol)

  completesChain
}
```

### Detection Points

In cps2, we need to detect this at TWO points:

#### 1. Signature Generation Phase (Cps2SignaturePhase)

When generating `method_async`:
- Check if return type is a chain intermediate
- If YES: return type should be `DelayedXxx[F, ...]` instead of `F[OriginalReturn]`
- Need to either:
  - Look up existing `DelayedXxx` type, OR
  - Generate one automatically

#### 2. Call-Site Transformation Phase (Cps2ImplementationPhase)

When transforming `obj.method(asyncLambda)`:
- Check if result type derives from `CallChainAsyncShiftSubst`
- If YES: Don't wrap in `flatMap`, preserve chain structure
- Track chain state in `CpsTree` (like `CallChainSubstCpsTree`)

## Auto-Generation Strategy

When generating both delayed classes and shifted methods in the same pass, we face an ordering problem:
- When processing `myLazyMap`, we need `DelayedLazyTransform`
- But we haven't generated it yet

### Solution: Two-Phase Template Processing

```scala
override def transformTemplate(tree: tpd.Template)(using Context): tpd.Tree = {
  // PHASE 1: Generate delayed class signatures for function-storing classes
  val delayedClasses = tree.body.collect {
    case cd: TypeDef if isFunctionStoringClass(cd) =>
      generateDelayedClassSignature(cd)
  }.flatten

  // Enter delayed class symbols into scope
  delayedClasses.foreach(_.symbol.entered)

  // PHASE 2: Process methods - now delayed types are available for lookup
  val shiftedMethods = tree.body.collect {
    case dd: DefDef if hasImpureFunctionParams(dd) =>
      generateShiftedMethod(dd)  // Can now find delayed types
  }.flatten

  // Combine everything
  tpd.cpy.Template(tree)(body = tree.body ++ delayedClasses ++ shiftedMethods)
}
```

### Key Insight

The delayed class only needs its **signature** (symbol + type) to be available for lookup.
The actual **body** can be filled in later (or even left as `???` stubs).

This mirrors how we handle shifted methods - generate signatures in Phase 1, fill bodies in Phase 2.

## Implementation Options for cps2

### Option A: Known Types Only (Conservative)

Maintain a list of known chain-intermediate types with their delayed wrappers:

```scala
object KnownChainTypes {
  val mappings = Map(
    "scala.collection.WithFilter" -> "cps.runtime.DelayedWithFilter",
    // Add more as library grows
  )

  def getDelayedType(originalReturn: Type)(using Context): Option[Type] =
    mappings.get(originalReturn.typeSymbol.fullName.toString).map(...)
}
```

Pro: Safe, predictable
Con: Requires manual maintenance

### Option B: Annotation-Based

Users annotate methods that should use chain substitution:

```scala
@callChainSubst[DelayedWithFilter]
def withFilter(p: A => Boolean): WithFilter[A, C]
```

Pro: Explicit, user-controlled
Con: Requires annotation on all such methods

### Option C: Automatic Detection + Generation

Detect intermediate types automatically and generate delayed wrappers:

```scala
// For original type:
class WithFilter[A, C[_]] {
  def map[B](f: A => B): C[B]
  def flatMap[B](f: A => IterableOnce[B]): C[B]
  def foreach[U](f: A => U): Unit
  def withFilter(q: A => Boolean): WithFilter[A, C]
}

// Auto-generate:
class DelayedWithFilter[F[_], A, C[_]](
    m: CpsMonad[F],
    original: () => WithFilter[A, C],  // Delayed creation
    predicates: List[A => F[Boolean]]  // Accumulated predicates
) extends CallChainAsyncShiftSubst[F, WithFilter[A, C], F[WithFilter[A, C]]] {

  def _finishChain: F[WithFilter[A, C]] = ???

  def map[B](f: A => B): F[C[B]] = ???
  def map_async[B](f: A => F[B]): F[C[B]] = ???
  // etc.
}
```

Pro: Fully automatic
Con: Complex, may generate suboptimal code

### Recommended: Option A + B

Start with known types (Option A), allow user extensions via annotation (Option B).

## Capability-Based Insight for cps2

With capture checking, we have additional type information:

```scala
// When p captures CPS capability:
def withFilter(p: A ->{cps} Boolean): WithFilter[A, C]

// The return type WithFilter[A, C] doesn't capture cps
// But the operation is "pending" - it depends on capability
```

Key insight: The capture set tells us the lambda is async, but the return type
doesn't reflect this. For chain-intermediate types, we need to:

1. Detect the method returns a non-capturing intermediate type
2. Transform to return a delayed wrapper that CAN handle async operations
3. The delayed wrapper's methods accept `A => F[B]` and return `F[Result]`

## Signature Transformation for Chain Methods

### Original Method
```scala
def withFilter[A, C[_]](p: A => Boolean): WithFilter[A, C]
```

### Standard Shifted (WRONG for chains)
```scala
def withFilter_async[F[_], A, C[_]](m: CpsTryMonad[F])(p: A => F[Boolean]): F[WithFilter[A, C]]
```

### Chain-Aware Shifted (CORRECT)
```scala
def withFilter_async[F[_], A, C[_]](m: CpsTryMonad[F])(p: A => F[Boolean]): DelayedWithFilter[F, A, C]
```

The key difference is the return type:
- Standard: `F[OriginalReturn]`
- Chain-aware: `DelayedWrapper[F, ...]`

## Implementation Plan

### Phase 1: Infrastructure
1. Define `CallChainAsyncShiftSubst` trait in cps2 library
2. Create `DelayedWithFilter` implementation

### Phase 2: Detection
1. Add `isCallChainIntermediateType` check to ShiftedMethodGenerator
2. For known intermediate types, generate correct return type
3. Add `@callChainSubst` annotation for user extension

### Phase 3: Transformation
1. Add `CallChainSubstCpsTree` to CPS tree structure
2. Detect when shifted method returns `CallChainAsyncShiftSubst`
3. Propagate chain state through subsequent method calls
4. Call `_finishChain` when chain ends

### Phase 4: Testing
1. Test `for` comprehensions with async `if` guards
2. Test chained `withFilter` calls
3. Test mixed sync/async operations in chains
