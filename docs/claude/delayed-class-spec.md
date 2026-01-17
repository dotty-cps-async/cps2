# Delayed Class Specification

## Overview

When a class stores function arguments (e.g., `LazyTransform[A, B](f: A => B)`), we need a "delayed" variant that:
1. Stores async functions instead (`f: A => F[B]`)
2. Provides shifted versions of all original methods
3. Can complete the chain with `_finishChain`

## Terminology

- **Original class**: User-written class that stores functions (e.g., `LazyTransform`)
- **Delayed class**: Auto-generated class for async support (e.g., `LazyTransform_cps`)
- **Stored function**: Function-typed constructor parameter that's saved for later use

## Example

### Original

```scala
class LazyTransform[A, B](source: List[A], val f: A => B):
  def toList: List[B] = source.map(f)
  def map[C](g: B => C): LazyTransform[A, C] = LazyTransform(source, f.andThen(g))
```

### Generated Delayed Class

```scala
@generatedDelayed
trait LazyTransform_cps[F[_], A, B](
    source: List[A],
    m: CpsTryMonad[F],
    val f: A => F[B]
):
  // Terminal method - applies stored async function
  def toList: F[List[B]] =
    // Async map using stored f
    source.foldLeft(m.pure(List.empty[B])) { (accF, a) =>
      m.flatMap(accF)(acc => m.map(f(a))(b => acc :+ b))
    }

  // Chain continuation - only shifted version needed
  // For sync g, caller wraps: map(x => m.pure(g(x)))
  def map[C](g: B => F[C]): LazyTransform_cps[F, A, C] =
    LazyTransform_cps(source, m, a => m.flatMap(f(a))(g))

  def _finishChain: F[LazyTransform[A, B]] =
    // Reconstructs original class by wrapping async functions with await
    // Does NOT run any methods - just converts delayed class back to original typing
    cpsPhase1Scaffold(m,
      new LazyTransform(source, (x: A) => scaffoldAwait(f(x)))
    )
```

Note:
- No base trait needed - `_finishChain` is added directly
- Only shifted versions of methods - for sync functions, caller wraps with `m.pure`

## `_finishChain` Semantics

`_finishChain` allows exiting from the delayed chain back to normal typing. It:

1. **Does NOT run any methods** - just reconstructs the original class
2. **Wraps async functions with await** - converts `A => F[B]` back to `A => B` using `scaffoldAwait`
3. **Returns `F[OriginalClass]`** - the original class wrapped in the effect monad

### Structure

For delayed class:
```scala
class Foo_cps[F[_], T1, T2](m: CpsTryMonad[F], p1: P1, f: T1 => F[T2])
```

The `_finishChain` body is:
```scala
def _finishChain: F[Foo[T1, T2]] =
  cpsPhase1Scaffold[F, Foo[T1, T2], F[Foo[T1, T2]]](
    m,
    new Foo[T1, T2](p1, (x: T1) => scaffoldAwait[F, T2](f(x)))
  )
```

Where:
- `p1` - non-function params passed directly
- `(x: T1) => scaffoldAwait(f(x))` - sync wrapper around async function `f`
- `scaffoldAwait` will be transformed to actual await in Phase 2

## Generation Rules

### 1. Class Structure

**For original class:**
```scala
class Foo[T1, ..., Tn](p1: P1, ..., pk: Pk, f1: A1 => B1, ..., fm: Am => Bm)
```

**Generate:**
```scala
@generatedDelayed
trait Foo_cps[F[_], T1, ..., Tn](
    p1: P1, ..., pk: Pk,      // Non-function params unchanged
    m: CpsTryMonad[F],         // Add monad param
    f1: A1 => F[B1], ..., fm: Am => F[Bm]  // Transform function params
):
  def _finishChain: F[Foo[T1, ..., Tn]]  // Added directly, no base trait
```

### 2. Type Parameters

- Prepend `F[_]` to type parameters
- Keep all original type parameters

### 3. Constructor Parameters

For each constructor parameter:
- **Non-function**: Keep as-is
- **Function `A => B`**: Transform to `A => F[B]`
- **Add**: `m: CpsTryMonad[F]` parameter (after non-function params, before function params)

### 4. Methods

For each method in original class, generate only the **shifted version** with function params transformed to `A => F[B]`.

For sync functions, caller wraps with `m.pure`: `x => m.pure(syncF(x))`

#### 4.1 Terminal Methods (return non-function-storing type)

**Original:**
```scala
def foo(x: X, f: A => B): ReturnType
```

**Generate:**
```scala
def foo(x: X, f: A => F[B]): F[ReturnType]
```

The body applies stored async functions where original used sync functions.

#### 4.2 Chain Methods (return same or another function-storing type)

**Original:**
```scala
def bar(g: B => C): Foo[X, Y]
```

**Generate:**
```scala
def bar(g: B => F[C]): Foo_cps[F, X, Y]
```

Returns new delayed instance, composing functions appropriately.

#### 4.3 Methods without function params

**Original:**
```scala
def baz(x: X): ReturnType
```

**Generate:**
```scala
def baz(x: X): F[ReturnType]  // or Foo_cps[F, ...] for chain methods
```

No signature change except return type wrapped in F (or delayed type).

### 5. Annotation on Original Class

After generating `Foo_cps`, add to original `Foo`:
```scala
@delayedVariant[Foo_cps]
class Foo[T1, ..., Tn](...)
```

### 6. Placement

Generated class goes in the companion object of the original:
```scala
object Foo:
  @generatedDelayed
  class Foo_cps[F[_], ...](...)
```

## Implementation Phases

### Phase A: Symbol Creation (in Cps2SignaturePhase)

1. Detect function-storing classes
2. Check if `@delayedVariant` already present (user-provided)
3. If not present:
   a. Create `Foo_cps` class symbol in companion object
   b. Create `@delayedVariant[Foo_cps]` annotation
   c. Add annotation to original class symbol

### Phase B: Body Generation (in Cps2ImplementationPhase)

1. Find classes with `@generatedDelayed` annotation
2. For each, generate method bodies by transforming original methods

## Discovery Flow

When generating shifted methods that return a function-storing type:

1. Check auto-generated registry (from earlier in same compilation)
2. Check for `@delayedVariant[T]` annotation on return type's class
3. Check known library mappings (e.g., `WithFilter` → `DelayedWithFilter`)
4. Try naming conventions: `Delayed` + Name, Name + `_cps`, Name + `Async`
5. If class is function-storing but no delayed type found yet, create forward `TypeRef`
6. Forward references are resolved later when the type exists (order-independent)

## Design Decisions

1. **Method signatures**: Generate correct signatures for all methods including `_finishChain`.

2. **Method bodies**: Generate stubs with `???` for now. Full transformation can be added later.

3. **No base trait**: Delayed class extends `Object` directly. The `_finishChain` method is added directly to each generated class. This avoids complexity with type parameter references in parent types.

4. **Forward references**: For cross-file compilation, use `TypeRef` by name when delayed type doesn't exist yet. Makes compilation order-independent.

5. **Validation**: On-demand. When generating call-chain rewrite, if method with expected signature doesn't exist in the delayed class, generate a compile error.

## Next Steps

1. ~~Generate delayed class symbols with type params~~ ✓
2. ~~Add `_finishChain` method directly~~ ✓
3. ~~Forward references for cross-file compilation~~ ✓
4. Implement Phase B (body generation) - currently stubs
5. Add CaptureAnalysis for capability detection at call sites
6. Implement call-site rewriting logic
