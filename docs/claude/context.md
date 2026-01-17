# CPS2 Development Context

## Project Vision
cps2 is a fresh implementation of async/await for Scala 3.8+ that:
- Uses capabilities (capture checking) instead of CpsDirect
- Will gradually grow to have all functionality of dotty-cps-async
- Simpler design due to type-system-based detection

## Key Files

### cps2 (this project)
- `/Users/rssh/work/oss/cps2/library/src/main/scala/cps/CpsRuntime.scala` - Core types (CpsCapability, CpsMonad, CpsMonadContext)
- `/Users/rssh/work/oss/cps2/library/src/main/scala/cps/Async.scala` - async/await definitions
- `/Users/rssh/work/oss/cps2/plugin/src/main/scala/cps/plugin/cps2/Cps2Plugin.scala` - Compiler plugin

### dotty-cps-async (reference implementation)
- `/Users/rssh/work/oss/dotty-cps/dotty-cps-async/compiler-plugin/src/main/scala/cps/plugin/ShiftedMethodGenerator.scala` - Auto-generates shifted methods
- `/Users/rssh/work/oss/dotty-cps/dotty-cps-async/compiler-plugin/src/main/scala/cps/plugin/forest/ApplyTransform.scala` - HOF transformation logic
- `/Users/rssh/work/oss/dotty-cps/dotty-cps-async/compiler-plugin/src/main/scala/cps/plugin/forest/ApplyArg.scala` - Argument classification
- `/Users/rssh/work/oss/dotty-cps/dotty-cps-async/compiler-plugin/src/main/scala/cps/plugin/forest/CpsTree.scala` - CPS expression representation
- `/Users/rssh/work/oss/dotty-cps/dotty-cps-async/shared/src/main/scala/cps/runtime/IterableAsyncShift.scala` - Library shifted implementations

### Scala 3.8 Compiler (capture checking)
- `/Users/rssh/packages/dotty/dotty/compiler/src/dotty/tools/dotc/cc/CapturingType.scala` - CapturingType extractor
- `/Users/rssh/packages/dotty/dotty/compiler/src/dotty/tools/dotc/cc/CaptureSet.scala` - CaptureSet class
- `/Users/rssh/packages/dotty/dotty/compiler/src/dotty/tools/dotc/cc/CaptureOps.scala` - Capture operations
- `/Users/rssh/packages/dotty/dotty/library/src/scala/caps/package.scala` - Capability traits (SharedCapability, etc.)

## Key Design Decisions

### CPS Transformation Algorithm
Both dotty-cps-async and cps2 use the same classic top-down CPS transformation algorithm (well-studied since 1960s). What differs is:
- The context type for "direct" encoding
- How we detect which functions need async variants (capture checking in cps2)

### Direct Context Encoding
Direct context encoding allows writing async code that looks synchronous:

**dotty-cps-async:**
```scala
def fetch(url: String)(using CpsDirect[Future]): String
```

**cps2:**
```scala
def fetch(url: String)(using CpsTryMonadContext[F]): T
```

**Transformation (same for both):**
```scala
// Source (direct style)
f(args)(using CpsDirect[F]): T           // dotty-cps-async
f(args)(using CpsTryMonadContext[F]): T  // cps2

// Transformed (monadic style)
f(m, o)(shifted-args): F[T]
```

### CpsMonadContext IS the Capability

The key insight: `CpsMonadContext[F]` is both:
1. The monad context (provides monad operations)
2. The capability instance (tracked by capture checking)

### Unified Detection via Capture Polymorphism

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

### Direct Functions

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

### Detection via Capture Sets

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

### Where CPS Transformation Applies

1. **Direct functions**: Result type captures `CpsMonadContext`
2. **Async blocks**: `async[F] { body }` creates the context

Inside these, lambdas that capture the context need their HOF calls rewritten to shifted versions.

### Shifted Method Generation
- In dotty-cps-async: Only for `@makeCps` annotated functions
- In cps2: For ALL higher-order functions (automatic)

Pattern:
```scala
// Original
def map[A, B](f: A => B): List[B]

// Generated
def map_async[F_SHIFT[_], A, B](m: CpsTryMonad[F_SHIFT])(f: A => F_SHIFT[B]): F_SHIFT[List[B]]
```

### CpsCapability Hierarchy (Scala 3.8)
```scala
sealed trait Capability extends Any           // Cannot extend directly
trait SharedCapability extends Capability     // For shared capabilities
trait ExclusiveCapability extends Capability  // For exclusive capabilities
trait Control extends SharedCapability        // Like boundary.Label

// CPS uses SharedCapability
trait CpsCapability extends caps.SharedCapability
```

## Transformation Flow

```
1. async[Future] { list.map(x => await(fetch(x))) }

2. Plugin Phase 1 (Signatures):
   - Generate map_async for any method with function params

3. Plugin Phase 2 (Implementation):
   - Detect: lambda (x => await(fetch(x))) has type Int ->{ctx} String
   - Check: capture set contains CpsCapability
   - Find: list.map_async shifted version
   - Transform lambda: remove await, return F[T]
   - Rewrite: list.map_async(monad)(x => fetch(x))
```

## Capture Checking API

Key types from `/Users/rssh/packages/dotty/dotty/compiler/src/dotty/tools/dotc/cc/`:

```scala
// CapturingType.scala - extractor for T^{refs}
object CapturingType:
  def unapply(tp: AnnotatedType)(using Context): Option[(Type, CaptureSet)]

// CaptureSet.scala
sealed abstract class CaptureSet:
  def elems: Refs  // The capability elements

// CaptureOps.scala - extension methods
extension (tp: Type)
  def captureSet(using Context): CaptureSet
```

Usage pattern for detection:
```scala
def hasCpsCapability(tpe: Type)(using Context): Boolean =
  tpe match
    case CapturingType(parent, refs) =>
      refs.elems.exists { cap =>
        cap.widen.classSymbol.derivesFrom(CpsCapabilityClass)
      }
    case _ =>
      val cs = tpe.captureSet
      cs.elems.exists { cap =>
        cap.widen.classSymbol.derivesFrom(CpsCapabilityClass)
      }
```

## Implementation Status

### Done
- CpsCapability extends SharedCapability (fixed for Scala 3.8)
- Basic plugin structure with two phases
- Core library types (CpsMonad, CpsMonadContext, etc.)
- **ShiftedMethodGenerator** - generates `_async` shifted versions for impure HOFs
- **CpsTransformHelper** - type transformation utilities (cpsTransformedType)
- **CC-aware function detection** - only generates for impure functions when CC enabled
- **Warning for non-CC mode** - warns if capture checking is not enabled
- **CaptureAnalysis** - capture set analysis utilities (`hasCpsCapability`, `isDirectFunction`)
- **@directFunction annotation** - marker for direct functions, added by plugin
- **Direct function detection** - detects functions whose result captures CpsMonadContext
- **Extension method support** - correctly generates shifted versions for extension methods

### Impure Function Detection (Key Implementation Detail)
When CC is enabled, impure functions (`A => B`) use type alias `ImpureFunctionN`:
```scala
type ImpureFunction1[-T, +R] = {cap} Function1[T, R]
```
Detection must check `tp.typeSymbol.name.isImpureFunction` BEFORE `widen.dealias`,
because dealiasing strips the `ImpureFunction1` alias back to `Function1`.

### New Plugin Files
- `/plugin/src/main/scala/cps/plugin/cps2/ShiftedMethodGenerator.scala`
- `/plugin/src/main/scala/cps/plugin/cps2/CpsTransformHelper.scala`

### Test Files
- `/plugin/test-files/basic/Sample.scala` - context function test
- `/plugin/test-files/basic/ExtensionSample.scala` - extension method tests
- `/plugin/test-files/basic/CallChainSample.scala` - call chain detection tests

### Call Chain Substitution Detection

Methods that **store** function arguments (like `withFilter`) are detected automatically:
- Check 1: Return type's class has function-typed constructor parameters
- Check 2: Return type is a known call-chain type (WithFilter, Iterator, View)

With capture checking, this manifests as capabilities flowing from function to return type:
```scala
def myLazyMap[A, B](list: List[A])(f: A => B): LazyTransform[A, B]^{f}
//                                                               ^^^^
// f's capabilities flow to return type because f is STORED
```

### Delayed Type Lookup

For call-chain methods, the shifted version returns a delayed wrapper instead of `F[T]`:
```scala
// Original:  def myLazyMap(...)(f: A => B): LazyTransform[A, B]
// Shifted:   def myLazyMap_async[F[_]](m)(...)(f: A => F[B]): DelayedLazyTransform[F, A, B]
```

Lookup strategy (in `findDelayedType`):
1. Check `knownDelayedTypes` map (library-provided, e.g., `WithFilter` → `DelayedWithFilter`)
2. Try naming conventions in same scope: `Delayed` + Name, Name + `_cps`, Name + `Async`
3. If not found, fallback to `F[OriginalType]`

### Two-Phase Template Processing

The plugin now uses two-phase template processing:
1. **Phase A**: Scan for function-storing classes, register delayed class needs
2. **Phase B**: Generate shifted methods (delayed type lookup available)

This ensures delayed types are discoverable before shifted methods are generated.

### TODO
1. ~~Complete ShiftedMethodGenerator~~ ✓ (type signatures done, body generation pending)
2. ~~Call chain substitution detection~~ ✓ (detection + delayed type lookup working)
3. ~~Delayed type lookup for call-chain methods~~ ✓ (by naming convention + known types)
4. ~~Two-phase template processing~~ ✓ (function-storing classes detected first)
5. ~~Annotation infrastructure~~ ✓ (`@delayedVariant` added)
6. ~~Auto-generate delayed class symbols with type params~~ ✓ (basic class generation works!)
7. ~~Add `_finishChain` method to generated delayed classes~~ ✓
8. Add CaptureAnalysis for capability detection at call sites
10. Add call-site rewriting logic
11. Fill shifted method bodies with actual CPS transformation
12. Library support (AsyncShift implementations, DelayedWithFilter, etc.)
13. Full async block transformation

### Delayed Class Generation Status

The plugin now auto-generates delayed class signatures:
- Detects function-storing classes (e.g., `LazyTransform[A, B]`)
- Generates delayed class symbols with type params (e.g., `LazyTransform_cps[F_SHIFT, A, B]`)
- Uses `newCompleteClassSymbol` with pre-created scope
- Type params: `F_SHIFT[_]` (higher-kinded) + original class type params
- Generated class is entered into owner's scope
- Shifted methods find and use auto-generated delayed classes

Generated delayed classes:
- Extend `Object` (no base trait needed - simpler)
- Include `_finishChain: F[Original[...]]` method directly
- Use forward TypeRef for cross-file references (order-independent)

### Forward References for Cross-File Compilation

When a delayed type doesn't exist yet (e.g., `LazyA` references `LazyB` before `LazyB_cps` is generated),
the plugin creates a **forward TypeRef** instead of falling back to `F[T]`:

```scala
// Instead of: F[LazyB[A, C]]
// Creates:    TypeRef(crossref, LazyB_cps)[F, A, C]
```

This makes cross-file compilation order-independent:
- Forward references are resolved later when the type exists
- Works within same compilation (later files) or from TASTY (separate compilation)

## How to Reload Context

When starting a new session, read these files:
1. This file (`docs/claude/context.md`)
2. The plan file (`docs/claude/plan.md`)
3. Key source files listed above as needed

This provides full context for continuing development.
