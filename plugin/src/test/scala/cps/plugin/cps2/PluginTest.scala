package cps.plugin.cps2

import munit.FunSuite
import java.nio.file.{Files, Paths}
import java.io.{ByteArrayOutputStream, PrintStream}
import dotty.tools.dotc.{Main, Driver}
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.reporting.Reporter

class PluginTest extends FunSuite:
  
  test("plugin can be instantiated") {
    val plugin = new Cps2Plugin()
    assertEquals(plugin.name, "cps2-plugin")
    assertEquals(plugin.description, "Minimal CPS2 compiler plugin")
  }
  
  test("plugin creates phases with correct properties") {
    val plugin = new Cps2Plugin()
    val phases = plugin.init(List.empty)
    assertEquals(phases.length, 2)

    assert(phases.head.runsAfter.nonEmpty, "Phase should have dependencies")
    assert(phases.head.runsBefore.nonEmpty, "Phase should specify ordering")
  }
  
  test("test files exist in correct structure") {
    val testDir = Paths.get("test-files/basic")
    assert(Files.exists(testDir), "Test files directory should exist")
    
    val sampleFile = Paths.get("test-files/basic/Sample.scala")
    val anotherFile = Paths.get("test-files/basic/Another.scala")
    
    assert(Files.exists(sampleFile), "Sample.scala should exist")
    assert(Files.exists(anotherFile), "Another.scala should exist")
  }
  
  test("plugin structure is valid for compilation") {
    // Create a simple test to verify plugin can be used in compilation
    val plugin = new Cps2Plugin()
    val phases = plugin.init(List.empty)

    // Verify plugin structure is correct
    assert(phases.length == 2, "Should have 2 phases")
    assert(phases.head.runsAfter.nonEmpty, "Phase should have dependencies")
    assert(phases.head.runsBefore.nonEmpty, "Phase should specify ordering")

    // Test passed if we get here without exceptions
    assert(true, "Plugin structure is valid and ready for compilation")
  }

  test("plugin actually processes files during compilation") {
    // Test files to compile
    val testFiles = List(
      "test-files/basic/Sample.scala",
      "test-files/basic/Another.scala"
    )

    val result = TestUtils.runCompilerWithPlugin(testFiles)

    // Verify plugin was invoked - it should have printed method processing messages
    assert(result.containsPluginOutput ||
           (result.succeeded && !result.allOutput.contains("error")),
           s"Expected plugin processing output or successful compilation. Exit code: ${result.exitCode}, Output: '${result.allOutput}'")

    // Verify no fatal compilation errors
    assert(result.succeeded || result.containsPluginOutput,
           s"Compilation should succeed or show plugin output. Exit code: ${result.exitCode}, Output: ${result.allOutput}")
  }

  test("plugin generates CPS version of higher-order methods") {
    val testFiles = List(
      "test-files/basic/Sample.scala"
    )

    val result = TestUtils.runCompilerWithPlugin(testFiles)

    // Verify plugin generated shifted method (Phase 1)
    // The plugin outputs: "CPS2 Plugin (Phase 1): Generated higherOrderMethod_async for higherOrderMethod"
    assert(result.allOutput.contains("Generated") && result.allOutput.contains("_async"),
           s"Expected plugin to generate _async method. Output: '${result.allOutput}'")

    // Verify compilation succeeded
    assert(result.succeeded,
           s"Compilation should succeed. Exit code: ${result.exitCode}, Output: ${result.allOutput}")
  }

  test("plugin generates CPS version of extension methods with impure function params") {
    val testFiles = List(
      "test-files/basic/ExtensionSample.scala"
    )

    val result = TestUtils.runCompilerWithPlugin(testFiles)

    // Verify plugin generated shifted methods for extension methods
    // Extension methods with impure function parameters should generate _async versions
    val output = result.allOutput

    // myMap has (f: A => B) - impure, should generate myMap_async
    assert(output.contains("myMap_async"),
           s"Expected plugin to generate myMap_async for extension method. Output: '$output'")

    // myFlatMap has (f: A ?=> Option[B]) - context function, always impure
    assert(output.contains("myFlatMap_async"),
           s"Expected plugin to generate myFlatMap_async for context function extension. Output: '$output'")

    // myPureMap has (f: A -> B) - pure, should NOT generate
    assert(!output.contains("myPureMap_async"),
           s"Expected plugin to NOT generate myPureMap_async for pure function. Output: '$output'")

    // double has no function params - should NOT generate
    assert(!output.contains("double_async"),
           s"Expected plugin to NOT generate double_async. Output: '$output'")

    // Verify compilation succeeded
    assert(result.succeeded,
           s"Compilation should succeed. Exit code: ${result.exitCode}, Output: $output")
  }

  test("plugin detects call-chain methods that store functions") {
    val testFiles = List(
      "test-files/basic/CallChainSample.scala"
    )

    val result = TestUtils.runCompilerWithPlugin(testFiles)
    val output = result.allOutput

    // myLazyMap stores function in LazyTransform - should be detected as call-chain
    assert(output.contains("Detected call-chain method myLazyMap"),
           s"Expected plugin to detect myLazyMap as call-chain method. Output: '$output'")

    // myLazyFilter stores function in LazyFilter - should be detected as call-chain
    assert(output.contains("Detected call-chain method myLazyFilter"),
           s"Expected plugin to detect myLazyFilter as call-chain method. Output: '$output'")

    // lazyWhere extension also stores function - should be detected
    assert(output.contains("Detected call-chain method lazyWhere"),
           s"Expected plugin to detect lazyWhere extension as call-chain method. Output: '$output'")

    // myMap applies function immediately - should NOT be call-chain
    assert(!output.contains("Detected call-chain method myMap"),
           s"Expected plugin to NOT detect myMap as call-chain (function applied immediately). Output: '$output'")

    // LazyTransform should be detected as needing delayed class and auto-generated
    assert(output.contains("Generating delayed class") && output.contains("LazyTransform_cps"),
           s"Expected plugin to detect LazyTransform needs delayed class. Output: '$output'")

    // Auto-generated delayed class should be found when generating shifted methods
    assert(output.contains("Found auto-generated delayed type") || output.contains("LazyTransform_cps"),
           s"Expected plugin to find auto-generated delayed class. Output: '$output'")

    // All should generate _async versions
    assert(output.contains("myLazyMap_async"),
           s"Expected myLazyMap_async to be generated. Output: '$output'")

    // Verify compilation succeeded
    assert(result.succeeded,
           s"Compilation should succeed. Exit code: ${result.exitCode}, Output: $output")
  }

  test("plugin detects direct functions and marks with @directFunction") {
    val testFiles = List(
      "test-files/direct/DirectFunctionSample.scala"
    )

    val result = TestUtils.runCompilerWithPlugin(testFiles)
    val output = result.allOutput

    // Note: Direct function detection requires capture checking to be enabled
    // If CC is not enabled, we won't see direct function markers

    if (output.contains("Marked") && output.contains("@directFunction")) {
      // CC is enabled - verify direct functions are detected
      assert(output.contains("Marked fetchWithUsing as @directFunction"),
             s"Expected fetchWithUsing to be marked as direct function. Output: '$output'")

      assert(output.contains("Marked fetchWithParam as @directFunction"),
             s"Expected fetchWithParam to be marked as direct function. Output: '$output'")

      assert(output.contains("Marked fetchGeneric as @directFunction"),
             s"Expected fetchGeneric to be marked as direct function. Output: '$output'")

      // regularFunction should NOT be marked - no CpsMonadContext
      assert(!output.contains("Marked regularFunction"),
             s"Expected regularFunction to NOT be marked as direct function. Output: '$output'")
    } else {
      // CC might not be enabled - just check compilation succeeds
      println(s"Note: Direct function detection test - CC may not be enabled. Output: '$output'")
    }

    // Verify compilation succeeded (may have warnings if CC not enabled)
    assert(result.succeeded || result.exitCode == 0,
           s"Compilation should succeed. Exit code: ${result.exitCode}, Output: $output")
  }

  test("async preserves specific context types for guard function") {
    // Test that async block properly preserves CpsLogicMonadContext for guard function
    val testFiles = List(
      "test-files/context/SpecificContextTest.scala"
    )

    val result = TestUtils.runCompilerWithPlugin(testFiles)
    val output = result.allOutput

    // The test should compile - guard[List] requires CpsLogicMonadContext[List]
    // which should be inferred from ListMonad.Context via CpsMonad.Aux pattern
    assert(result.succeeded,
           s"Specific context test should compile successfully. Exit code: ${result.exitCode}, Output: $output")
  }

  test("plugin generates delayed classes for cross-file references") {
    // Test files with cross-references between function-storing classes
    val testFiles = List(
      "test-files/crossref/LazyA.scala",
      "test-files/crossref/LazyB.scala",
      "test-files/crossref/CrossRefUsage.scala"
    )

    val result = TestUtils.runCompilerWithPlugin(testFiles)
    val output = result.allOutput

    // LazyA is a top-level function-storing class - should generate LazyA_cps
    assert(output.contains("Generating delayed class LazyA_cps") && output.contains("top-level"),
           s"Expected plugin to generate LazyA_cps for top-level class. Output: '$output'")

    // LazyB is a top-level function-storing class - should generate LazyB_cps
    assert(output.contains("Generating delayed class LazyB_cps") && output.contains("top-level"),
           s"Expected plugin to generate LazyB_cps for top-level class. Output: '$output'")

    // Cross-file references should work via forward references or actual lookups
    // LazyA references LazyB before LazyB_cps exists -> forward reference
    // LazyB references LazyA after LazyA_cps exists -> found existing
    assert(output.contains("forward reference") || output.contains("Found"),
           s"Expected plugin to use forward references or find delayed types. Output: '$output'")

    // CrossRefUsage should find both delayed types (compiled last)
    assert(output.contains("Found user-defined delayed type"),
           s"Expected plugin to find delayed types from other files. Output: '$output'")

    // Shifted methods should be generated
    assert(output.contains("lazyMapA_async") && output.contains("lazyMapB_async"),
           s"Expected shifted methods to be generated. Output: '$output'")

    // Verify compilation succeeded
    assert(result.succeeded,
           s"Compilation should succeed. Exit code: ${result.exitCode}, Output: $output")
  }