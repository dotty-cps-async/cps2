# Plan: Capability-Based Detection for CPS Higher-Order Function Transformation

## Problem Statement

When a higher-order function like `list.map(f)` is called inside an `async` block, and the function argument `f: A => B` uses `await`, we need to:
1. **Generate**: Create async variants (`map_async`) for higher-order functions
2. **Detect**: At call sites, determine which version (sync or async) to call

**Key insight for cps2**: With Scala 3.8 capture checking, the type system tracks which functions capture the CPS capability. This helps us know at generation time which functions need async variants - impure functions (that can capture) need shifting, pure functions don't.

**CPS transformation**: Both dotty-cps-async and cps2 use the same classic top-down CPS transformation algorithm (well-studied since 1960s). The difference is the context type used for "direct" encoding.

## Direct Context Encoding

Direct context encoding allows writing async code that looks synchronous by using a context parameter:

### dotty-cps-async
```scala
def fetch(url: String)(using CpsDirect[Future]): String
```

### cps2
```scala
def fetch(url: String)(using CpsTryMonadContext[F]): T
```

### Transformation (same for both)
```scala
// Source (direct style)
f(args)(using CpsDirect[F]): T      // dotty-cps-async
f(args)(using CpsTryMonadContext[F]): T  // cps2

// Transformed (monadic style)
f(m, o)(shifted-args): F[T]
```

The direct function can be called from an `async` block or another direct function:
```scala
def greet()(using CpsTryMonadContext[Future]) =
  val greeting = fetchGreeting()  // looks sync, is async
  println(greeting)

def main() = async[Future] { greet() }
```

## Current dotty-cps-async Approach (Reference)

From `/Users/rssh/work/oss/dotty-cps/dotty-cps-async`:

### Detection
- `AsyncKind` enum: `Sync`, `Async(internal)`, `AsyncLambda(bodyKind)`
- Recursive transformation tags every expression
- `ApplyArg.isAsyncLambda` checks if lambda body contains await

### Transformation
1. Find "shifted" method: `map` → `map_async` / `mapAsync` / `$cps`
2. Shifted signature: `def map_async[F[_], B](m: CpsMonad[F])(f: A => F[B]): F[List[B]]`
3. Transform lambda: remove `await`, return `F[B]` directly
4. Fallback: runtime await if no shifted method exists

### Library
- `AsyncShift[T]` typeclass with shifted methods
- Manual implementations: `ListAsyncShift`, `IterableAsyncShift`, etc.

### Shifted Method Generation
- In dotty-cps-async: Only for functions annotated with `@makeCps`
- **In cps2: For ALL functions with higher-order parameters** (automatic)

See `ShiftedMethodGenerator.scala` for implementation pattern.

## New Approach: Capability-Based Detection

### Key Insight

With capture checking enabled, the type system already tracks CPS capability:

```scala
async[Future] {
  // This lambda has type: Int ->{ctx} String  (captures ctx: CpsMonadContext)
  val f = (x: Int) => await(fetchData(x))

  // The compiler KNOWS f captures the CPS capability!
  list.map(f)  // Type error: map expects Int => String, got Int ->{ctx} String
}
```

**The capture set IS the detection mechanism** - no need for `AsyncKind` tagging.

### Benefits

1. **Type-driven**: Detection is a type check, not AST traversal
2. **Composable**: Capture sets compose automatically through calls
3. **Sound**: Compiler guarantees capability tracking
4. **Simpler plugin**: Less custom tracking logic needed

### Implementation Design

#### Step 1: Detect CPS Capture in Function Arguments

```scala
def capturesCpsCapability(argType: Type)(using Context): Boolean =
  argType match
    case CapturingType(parent, refs) =>
      refs.elems.exists(_.typeSymbol.isSubClass(defn.CpsCapabilityClass))
    case _ => false
```

#### Step 2: Shifted Method Resolution (same as dotty-cps-async)

Look for shifted version with naming conventions:
- `method_async`, `methodAsync`, `method$cps`

Or via `AsyncShift[T]` typeclass.

#### Step 3: Lambda Transformation

Original (inside async):
```scala
(x: Int) => {
  val a = await(f1(x))      // a: String
  val b = await(f2(a))      // b: Int
  a + b.toString            // String
}
// Type: Int ->{ctx} String
```

Transformed:
```scala
(x: Int) => {
  monad.flatMap(f1(x)) { a =>
    monad.map(f2(a)) { b =>
      a + b.toString
    }
  }
}
// Type: Int => F[String]
```

#### Step 4: Call Site Rewriting

```scala
// Before (type error with capture checking)
list.map(x => await(fetch(x)))

// After
list.map_async(monad)(x => fetch(x))
```

## Vision

**cps2** is a fresh implementation that will:
- Gradually grow to have full dotty-cps-async functionality
- Use capabilities instead of CpsDirect
- Be simpler due to type-system-based detection

## Implementation Plan

### Overview

The transformation has three parts:
1. **Generation**: Auto-generate `_async` shifted versions for HOFs with impure function params ✓
2. **Direct Function Detection**: Detect functions whose result captures `CpsMonadContext`, mark with `@directFunction` ✓
3. **CPS Transformation**: Transform direct function bodies and async/reify blocks (top-down CPS algorithm)

### Plugin Phases

```
Cps2SignaturePhase (before Pickler):
  A. Generate delayed class signatures for function-storing classes
  B. Generate shifted method signatures for HOFs
  C. Detect direct functions, add @directFunction annotation
  D. Add generated classes/methods to template

Cps2ImplementationPhase (after Inlining):
  - Fill shifted method bodies
  - Transform direct function bodies (CPS transform)
  - Transform async/reify block bodies

Cps2ErasurePhase (after Erasure) - TODO:
  - Transform direct function signatures: T^{ctx} → F[T]
```

### CpsPreprocessor Support ✓

Implemented two-stage async processing to support monad preprocessing (similar to dotty-cps-async 1.2.0).

#### What is CpsPreprocessor?

`CpsPreprocessor[F, C]` is a typeclass that enables AST transformations of async block bodies **before** CPS transformation occurs. This allows monads to customize how code inside async blocks is preprocessed at compile time.

**Use cases:**
- **Durable Monad**: Wrap `val` definitions with caching for replay-based recovery
- **Tracing Monad**: Insert logging/tracing calls around expressions
- **STM Monad**: Add savepoint markers for transactional memory
- **Profiling**: Wrap expressions with timing measurements

#### Two-Stage Architecture

```
async[F] { body }
    ↓
Stage 1 (AsyncBuilder.apply - transparent inline):
  - summonFrom checks if CpsPreprocessor[F, am.Context] exists
  - If found: pp.preprocess(body, ctx) expands inline
  - If not: pass body directly to Stage 2
    ↓
Stage 2 (asyncStage2 - plugin entry point):
  - Plugin performs actual CPS transformation
  - Preprocessor has already expanded, so await calls it inserted are visible
```

#### Key Design Decisions

**Why `transparent inline` for Stage 1?**
- Ensures preprocessor macro expands immediately (before Stage 2)
- Preprocessor can insert `await` calls that plugin will recognize
- Uses `summonFrom` for compile-time preprocessor lookup

**Why explicit context parameter in preprocess?**
- Enables generated code to directly reference context methods
- Example: `ctx.cached(stepIndex, valIndex) { originalExpr }`

**Why `CpsMonad.Aux[F, C]` type alias?**
- Avoids path-dependent type issues with `inline` parameters
- Captures context type `C` as explicit type parameter

#### Files Created/Modified

**New files:**
- `library/src/main/scala/cps/CpsPreprocessor.scala` - Typeclass definition

**Modified files:**
- `library/src/main/scala/cps/CpsRuntime.scala` - Added `CpsMonad.Aux[F, C]`
- `library/src/main/scala/cps/Async.scala` - Two-stage processing with `summonFrom`

#### Usage Example

```scala
// Define a preprocessor for your monad
given CpsPreprocessor[DurableMonad, DurableContext] with
  transparent inline def preprocess[A](inline body: A, inline ctx: DurableContext): A =
    ${ DurablePreprocessorMacro.impl[A]('body, 'ctx) }

// Use normally - preprocessing happens automatically
async[DurableMonad] {
  val x = await(fetchData())  // preprocessor can wrap this
  x + 1
}
```

#### Compatibility Note

Scala 3.8's capture checking required using path-dependent types (`am.Context`) inside `AsyncBuilder` rather than explicit type parameters, due to capability types in invariant positions.

### Factored Context Creation Pattern ✓

When implementing a monad that works with capture checking, the context creation must be factored out to allow capabilities to flow correctly through generated `_async` methods.

#### The Problem

When a monad's `apply` method creates context locally, each method gets its own fresh capability:

```scala
// PROBLEMATIC: Fresh capability created inside method
def apply[T](op: Context ?=> T): List[T] =
  given ctx: ListMonadContext = new ListMonadContext  // cap created here
  List(op)
```

When the plugin generates `apply_async`, it's a separate method with a different capability scope:
- `apply` creates fresh capability `cap`
- `apply_async` creates fresh capability `cap²`
- Capture checker sees them as incompatible: "cap in method apply is not visible from cap² in method apply_async"

#### The Solution: Factor Out Context Creation

Separate context creation from context usage:

```scala
// CORRECT: Factored context creation
object ListMonad extends CpsLogicMonad[List]:
  type Context = ListMonadContext

  def pure[A](a: A): List[A] = List(a)
  def flatMap[A, B](fa: List[A])(f: A => List[B]): List[B] = fa.flatMap(f)

  // Factory method - returns context with capability
  def createContext(): Context = new ListMonadContext

  // Application method - receives context as parameter, capability flows through
  def applyWithContext[T](ctx: Context)(op: Context ?=> T): List[T] =
    List(op(using ctx))

  // Entry point - composes the two
  def apply[T](op: Context ?=> T): List[T] =
    applyWithContext(createContext())(op)
```

#### Why This Works

1. **`createContext()`** returns a context - the capability is tied to the returned value
2. **`applyWithContext(ctx)`** receives context as a parameter - capability **flows through** like `CanThrow`
3. When plugin generates `_async` versions, the capability properly propagates through parameters

This is analogous to how `CanThrow` works - the capability is passed as a parameter rather than created locally inside the method that uses it.

#### Comparison with boundary/Label

The `boundary` API uses `inline` to avoid this issue:

```scala
// boundary.apply is inline - body is in same scope as Label creation
inline def apply[T](inline body: Label[T] ?=> T): T =
  val local = Label[T]()
  try body(using local)
  catch ...
```

Since `boundary.apply` is `inline`, the `Label` and body are in the **same scope** - no separate method, no different fresh capability.

For monads where `apply` cannot be inline (because we need shifted versions), the factored pattern is the solution.

#### CpsCapability Trait

`CpsCapability` should extend `caps.Control` (not just `SharedCapability`):

```scala
trait CpsCapability extends caps.Control
```

The `Control` trait is documented as: "Marker trait for capabilities that capture some continuation or return point in the stack. Examples are exceptions, Label, CanThrow or **Async contexts**."

### Phase 1: Shifted Method Generation ✓

Current cps2 plugin has basic structure but needs proper type transformation.

#### Analysis of ShiftedMethodGenerator Components

The dotty-cps-async `ShiftedMethodGenerator` has these parts:

1. **Type Signature Generation** (ESSENTIAL):
   - `shiftedFunctionPolyType` - adds `F_SHIFT[_]` type param, creates new PolyType
   - `shiftedFunctionMethodType` - adds `m: CpsTryMonad[F_SHIFT]` param
   - `shiftedFunctionReturnType` - wraps return type in `F_SHIFT[_]`
   - `shiftParamTypes` - transforms `A => B` params to `A => F_SHIFT[B]`

2. **Detection** (SIMPLE):
   - `isFunction` - checks if type is function/method/poly
   - `isHightOrderByArg` - checks if method has function params
   - `selectHighOrderParamss` - selects function params

3. **Body Transformation** (COMPLEX - defer):
   - `transformFunсBody` - wraps in `cpsAsyncApply`, creates context lambda
   - `insertAwait` - wraps function calls with `await`

4. **Helper Dependencies**:
   - `CpsTransformHelper.cpsTransformedType` - core type transformer
   - `TransformUtil.substParamsMap` - parameter substitution

#### Minimal Subset Plan

**Step 1: Type Signature Only (Start Here)**
```scala
// Original
def myHOF[A, B](f: A => B): List[B]

// Generated (with stub body)
def myHOF_async[F_SHIFT[_], A, B](m: CpsTryMonad[F_SHIFT])(f: A => F_SHIFT[B]): F_SHIFT[List[B]] = ???
```

What we need:
- Port `shiftedFunctionPolyType` + `shiftedFunctionMethodType`
- Port `shiftParamTypes` (simple)
- Port `cpsTransformedType` from CpsTransformHelper (needed for type wrapping)
- Detection already exists in cps2 (`shouldGenerateCpsVersion`)

**Step 2: Simple Body (Later)**
- Just call original method and wrap result: `m.pure(original(...))`

**Step 3: Full Body Transformation (Much Later)**
- Port `transformFunсBody` with `await` insertion

#### Key Type Transformation Pattern

```scala
// Input type: def foo[A,B](f: A => B): R
// Output type structure:

PolyType(["F_SHIFT", "A", "B"])(           // Add F_SHIFT[_] type param
  bounds => [F_SHIFT <: [_] =>> Any, ...],
  result => MethodType(["m"])(              // Add monad param first
    _ => [CpsTryMonad[F_SHIFT]],
    _ => MethodType(["f"])(                 // Original params with shifted types
      _ => [A => F_SHIFT[B]],               // Function param shifted
      _ => F_SHIFT[R]                       // Return type wrapped
    )
  )
)
```

### Phase 2: Capability-Based Detection (New for cps2)

#### 2.1 Add Capture Set Analysis Utilities ✓

Created `CaptureAnalysis.scala` with utilities to inspect capture sets:

```scala
// In new file: CaptureAnalysis.scala
import dotty.tools.dotc.cc.{CapturingType, CaptureSet, CaptureOps}
import dotty.tools.dotc.cc.CaptureOps.captureSet

object CaptureAnalysis:

  /** Check if type's capture set contains CpsCapability or its subtypes */
  def hasCpsCapability(tpe: Type)(using Context): Boolean =
    tpe match
      case CapturingType(parent, refs) =>
        refs.elems.exists { cap =>
          cap.widen.classSymbol.derivesFrom(defn.CpsCapabilityClass)
        }
      case _ =>
        // Also check via captureSet extension method
        val cs = tpe.captureSet
        cs.elems.exists { cap =>
          cap.widen.classSymbol.derivesFrom(defn.CpsCapabilityClass)
        }

  /** Extract CPS capability reference from capture set */
  def extractCpsCapability(tpe: Type)(using Context): Option[TermRef] =
    val cs = tpe.captureSet
    cs.elems.collectFirst {
      case ref: TermRef if ref.widen.classSymbol.derivesFrom(defn.CpsCapabilityClass) => ref
    }
```

**Key types from compiler** (in `/Users/rssh/packages/dotty/dotty/compiler/src/dotty/tools/dotc/cc/`):
- `CapturingType(parent, refs)` - extractor for `T^{refs}`
- `CaptureSet` - sealed class with `elems: Refs` containing capabilities
- `captureSet` extension on `Type` - gets capture set of any type
- `CoreCapability` - capabilities that are also types (has `widen`, `typeSymbol`)

**Implementation**: Uses `Symbols.getClassIfDefined("cps.CpsMonadContext")` to look up the capability class dynamically.

#### 2.2 Implement Full CPS Transformation (TODO)

Before detecting async lambdas at call sites, we need to implement the full CPS transformation.
The detection at Apply nodes happens WITHIN the CPS transformation (not as plugin override).

**CPS Transformation scope:**
1. Bodies of direct functions (marked with `@directFunction`)
2. Bodies of `async[F] { ... }` / `reify[F] { ... }` blocks

**The transformation (top-down):**
1. Traverse body expressions recursively
2. At each Apply node, check if any argument captures `CpsMonadContext`
3. If yes, rewrite to shifted version
4. Transform the result with monad operations

#### 2.3 Detect Async Lambdas in Apply Arguments (part of CPS transform)

Within CPS transformation (not plugin override), when processing Apply nodes:

```scala
// Inside CpsTransformer (NOT plugin override - this is CPS tree traversal)
def cpsTransformApply(tree: tpd.Apply)(using Context): CpsTree =
  val asyncArgs = tree.args.zipWithIndex.filter { (arg, _) =>
    hasCpsCapability(arg.tpe)
  }

  if asyncArgs.nonEmpty then
    rewriteToShiftedCall(tree, asyncArgs)
  else
    CpsTree.pure(tree)
```

### Phase 3: Full CPS Transformation

The call-site rewriting is part of the full CPS transformation, which is applied inside:
1. **Direct functions**: Functions whose result type captures `CpsMonadContext`
2. **Async blocks**: `async[F] { body }` expressions

#### 3.1 CpsMonadContext IS the Capability

The key insight: `CpsMonadContext[F]` serves as both:
1. The monad context (provides monad operations)
2. The capability instance (tracked by capture checking)

#### 3.2 Unified Detection via Capture Polymorphism

With capture polymorphism, both direct functions and HOF calls with capturing lambdas
have `CpsMonadContext` in their result's capture set:

```scala
// Direct function - result captures ctx directly
def fetch(url: String)(using ctx: CpsMonadContext[F]): String^{ctx}

// HOF with capture polymorphism - result captures whatever f captures
def map[A, B](f: A ->{?} B): List[B]^{f}
// When called with f that captures ctx:
list.map(f)  // result: List[B]^{ctx}
```

**Detection differs in certainty**:
| Type | Detection | Certainty | Action |
|------|-----------|-----------|--------|
| Direct function | Result **contains** `CpsMonadContext` | Definite | Transform signature: `T^{ctx}` → `F[T]` |
| Higher-order function | Result **can contain** via abstract type param | Potential | Add variant: `map` → `map_async` |

- **Direct functions**: Result definitely captures CPS context → transform existing signature (after erasure)
- **HOFs with impure params**: Result can capture CPS context via abstract type parameter (depends on what caller passes) → add `_async` variant

#### 3.3 Direct Function Definition

A **direct function** is any function whose result type's capture set contains `CpsMonadContext`:

```scala
// All of these are direct functions - result captures ctx:

// Using parameter
def fetch(url: String)(using ctx: CpsMonadContext[Future]): String^{ctx}

// Regular parameter
def fetch(url: String, ctx: CpsMonadContext[Future]): String^{ctx}

// Context function
def fetch(url: String): CpsMonadContext[Future] ?=> String

// Extension method with CpsMonadContext in method signature
extension (s: String)
  def fetchExtension[F[_]](using ctx: CpsMonadContext[F]): String^{ctx}

// Extension method with CpsMonadContext in extension head
extension [F[_]](s: String)(using ctx: CpsMonadContext[F])
  def fetchFromHead: String^{ctx}
```

The capability can come from any parameter (context or non-context, including extension head) - what matters is that the result type captures it.

#### 3.4 Detection via Capture Sets

**Direct function detection**: Result type's capture set contains `CpsMonadContext`
```scala
def isDirectFunction(sym: Symbol)(using Context): Boolean =
  val resultType = sym.info.finalResultType
  resultType.captureSet.elems.exists { cap =>
    cap.widen.typeSymbol.derivesFrom(CpsMonadContextClass)
  }
```

**Async lambda detection**: Same check - capture set contains `CpsMonadContext`
```scala
def hasCpsCapability(tpe: Type)(using Context): Boolean =
  tpe.captureSet.elems.exists { cap =>
    cap.widen.typeSymbol.derivesFrom(CpsMonadContextClass)
  }
```

#### 3.5 CPS Transformation Flow

Inside a direct function or async block:
1. **Top-down traversal** of the body (classic CPS algorithm)
2. For each expression, determine if it's sync or async
3. For HOF calls where lambda captures `CpsMonadContext`, rewrite to shifted version

#### 3.6 Find Shifted Method

```scala
def findShiftedMethod(receiver: Type, methodName: Name)(using Context): Option[Symbol] =
  val candidates = Seq(
    methodName.toString + "_async",
    methodName.toString + "Async",
    methodName.toString + "$cps"
  )
  candidates.flatMap(name =>
    receiver.member(name.toTermName).symbol
  ).headOption
```

#### 3.7 Transform Lambda Argument

Transform lambda that captures CPS capability to return `F[T]`:

```scala
def transformAsyncLambda(lambda: Tree, monad: Tree)(using Context): Tree =
  // 1. Strip await calls, get underlying F[T] expressions
  // 2. Chain with monad.flatMap / monad.map
  // 3. Return lambda with type A => F[B] instead of A ->{ctx} B
```

#### 3.8 Rewrite Call Site

```scala
// Before: list.map(x => await(fetch(x)))
// After:  list.map_async(monad)(x => fetch(x))
```

### Phase 4: Library Support (Later)

#### 4.1 AsyncShift Typeclass (reuse pattern from dotty-cps-async)

```scala
trait AsyncShift[T]:
  // Shifted versions of common HOFs
```

#### 4.2 Stdlib Extensions

Provide shifted versions for:
- `List`, `Seq`, `Vector`, `Array`
- `Option`, `Either`, `Try`
- Common collection methods: `map`, `flatMap`, `filter`, `fold`, etc.

### Phase 5: Full Async Block Transformation (Later)

Transform entire `async[F] { body }` blocks:
- Detect all await calls
- Build CPS tree (similar to dotty-cps-async but simpler)
- Generate monadic chain

## Files to Create/Modify

### Phase 1 Files (Shifted Method Generation)
```
/Users/rssh/work/oss/cps2/plugin/src/main/scala/cps/plugin/cps2/
├── ShiftedMethodGenerator.scala  # NEW - Port from dotty-cps-async (minimal)
├── CpsTransformHelper.scala      # NEW - Port cpsTransformedType
└── Cps2Plugin.scala              # MODIFY - Use ShiftedMethodGenerator
```

### Phase 2 Files (Capability Detection - Later)
```
/Users/rssh/work/oss/cps2/plugin/src/main/scala/cps/plugin/cps2/
├── CaptureAnalysis.scala      # Capture set utilities
├── ShiftedMethodResolver.scala # Find _async methods
├── LambdaTransformer.scala    # Transform async lambdas
└── ApplyTransformer.scala     # Handle HOF calls
```

### Library (later)
```
/Users/rssh/work/oss/cps2/library/src/main/scala/cps/runtime/
├── AsyncShift.scala
├── ListAsyncShift.scala
└── ...
```

## Reference Files from dotty-cps-async

Key files to study/adapt:
- `ApplyTransform.scala` - HOF transformation logic
- `ApplyArg.scala` - Argument classification
- `CpsTree.scala` - CPS expression representation
- `IterableAsyncShift.scala` - Library implementation pattern

## Save Context

After exiting plan mode, save this plan and context to:
- `/Users/rssh/work/oss/cps2/docs/claude/plan.md` - This plan file
- `/Users/rssh/work/oss/cps2/docs/claude/context.md` - Development context with key files and design decisions

## Immediate Next Steps (Phase 1 Focus)

### Step 1.1: Create Minimal CpsTransformHelper
Port just `cpsTransformedType` function - the core type wrapper:
```scala
// cpsTransformedType(T, F) = F[T] for simple types
// cpsTransformedType(A => B, F) = A => F[B] for functions
```

Source: `/Users/rssh/work/oss/dotty-cps/dotty-cps-async/compiler-plugin/src/main/scala/cps/plugin/CpsTransformHelper.scala` (lines 112-131)

### Step 1.2: Create Minimal ShiftedMethodGenerator
Port type signature generation only (no body transformation):
- `shiftedFunctionPolyType` - creates new type with F_SHIFT[_] param
- `shiftedFunctionMethodType` - adds monad parameter
- `shiftParamTypes` - transforms function params
- `isFunction` - detection helper

Source: `/Users/rssh/work/oss/dotty-cps/dotty-cps-async/compiler-plugin/src/main/scala/cps/plugin/ShiftedMethodGenerator.scala`

### Step 1.3: Update Cps2Plugin
- Replace `createCpsSignature` with call to `ShiftedMethodGenerator`
- Change naming from `Cps` suffix to `_async` suffix (standard convention)

### Test Case for Phase 1
```scala
// Input method
def higherOrder[A, B](x: A)(f: A => B): B = f(x)

// Should generate
def higherOrder_async[F_SHIFT[_], A, B](m: CpsTryMonad[F_SHIFT])(x: A)(f: A => F_SHIFT[B]): F_SHIFT[B] = ???
```

### Later Steps
- Step 2: Simple body that calls original + wraps in monad.pure
- Step 3: Full body transformation with await insertion
- Phase 2: Capability detection and call-site rewriting

## Task: Generate Correct Delayed Class Signatures

### Problem

For call-chain methods (methods that store function arguments like `withFilter`, `LazyTransform`), we need to generate a delayed class variant:

```scala
// Original
class LazyTransform[A, B](source: List[A], f: A => B)

// Need to generate
class LazyTransform_cps[F[_], A, B](
    source: List[A],
    m: CpsTryMonad[F],
    f: A => F[B]
) extends CallChainAsyncShiftSubst[F, LazyTransform[A, B], F[LazyTransform[A, B]]]
```

### Current Status

- Plugin detects function-storing classes ✓
- Annotation infrastructure exists (`@delayedVariant`, `CallChainAsyncShiftSubst`) ✓
- Actual class generation fails due to TASTY Pickler requirements

### Challenge

Creating a class with type parameters (`F[_]`) in a compiler plugin is complex:
1. Need to create proper `PolyType` for the class
2. `ClassInfo` must have type params in `decls` scope
3. TASTY Pickler has specific requirements for `pickleNewType`

### Findings from Investigation (Option C)

Studied `Definitions.scala` pattern:
- Uses `LazyType` completer with `complete(denot)` method
- Inside completer: creates scope, adds type params with proper flags, builds `ClassInfo`
- Key flags: `TypeParam | Deferred | Private | Local`
- Type params added to scope via `scope.enter(sym)`

**Problem**: In a plugin, `denot.info_=` is protected to `dotc` package. Using `infoFn` callback instead causes NPE because `cls.thisType` isn't available during `infoFn` execution.

### Approach Options (Updated)

**Option A: Generate into companion object Template** (Suggested by user)
- Companion object's Template is being transformed
- Could add delayed class as nested class in companion
- Still need typed trees though

**Option B: Run before Typer phase**
- Generate `untpd.TypeDef` trees
- Let typer create proper symbols
- Challenge: Plugin must run early enough

**Option C: Macro annotation approach**
- User adds `@generateDelayed` annotation
- Macro generates the delayed class at compile time
- Pro: Macros have full typing support

**Option D: Two-pass compilation**
- First pass detects need, generates source file
- Second compilation includes generated source
- Pro: Full typing support
- Con: Complex build setup

### Current Status

- Detection works ✓
- Annotation infrastructure exists ✓
- Class generation deferred - falls back to `F[T]`
- Users can provide custom delayed classes via `@delayedVariant[Custom]`

### Next Investigation

Try Option A: Insert class into companion object's Template body during transformation.

### Acceptance Criteria

1. Plugin generates `Foo_cps[F[_], ...]` class for function-storing `Foo[...]`
2. Generated class extends `CallChainAsyncShiftSubst[F, Foo[...], F[Foo[...]]]`
3. Generated class has `_finishChain` method with correct signature
4. Compilation succeeds (TASTY Pickler accepts the class)
5. Generated class is usable in shifted method return types
