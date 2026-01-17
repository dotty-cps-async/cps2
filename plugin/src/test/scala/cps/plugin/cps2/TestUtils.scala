package cps.plugin.cps2

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*
import java.util.concurrent.TimeUnit

object TestUtils:

  case class CompilationResult(
    exitCode: Int,
    output: String,
    errorOutput: String
  ):
    def allOutput: String = output + errorOutput
    def succeeded: Boolean = exitCode == 0
    def containsPluginOutput: Boolean =
      allOutput.contains("CPS2 Plugin") && allOutput.contains("Generated")

  /**
   * Runs the Scala 3 compiler with the CPS2 plugin on the specified test files.
   *
   * @param testFiles List of paths to Scala files to compile
   * @param outputDir Output directory for compiled classes (default: "target/test-compilation-output")
   * @param pluginPath Path to the plugin classes (default: "target/scala-3.7.3/classes")
   * @param timeout Maximum time to wait for compilation (default: 30 seconds)
   * @param additionalArgs Additional compiler arguments
   * @return CompilationResult containing exit code and output
   */
  def runCompilerWithPlugin(
    testFiles: List[String],
    outputDir: String = "target/test-compilation-output",
    pluginPath: String = "target/scala-3.8.0-RC6/classes",
    timeout: Duration = 30.seconds,
    additionalArgs: List[String] = List.empty
  ): CompilationResult =
    // Create output directory if it doesn't exist
    val outDir = Paths.get(outputDir)
    if (!Files.exists(outDir)) {
      Files.createDirectories(outDir)
    }

    // Get test classpath that includes scala3-compiler
    val testClasspath = System.getProperty("java.class.path")

    // Build the compiler arguments
    val compilerArgs = List(
      s"-Xplugin:$pluginPath",
      "-usejavacp",  // Use Java classpath which includes scala libraries
      "-experimental",  // Enable experimental features (needed for CpsTryMonad)
      "-Vprint:cps2-signatures",  // Show output after our phase
      "-explain",  // Show detailed error explanations
      "-d", outputDir
    ) ++ additionalArgs ++ testFiles

    val cmd = s"java -cp $testClasspath dotty.tools.dotc.Main ${compilerArgs.mkString(" ")}"

    println(s"Running: $cmd")
    val process = Runtime.getRuntime.exec(cmd)

    val exitedInTime = process.waitFor(timeout.toSeconds, TimeUnit.SECONDS)

    if (exitedInTime) {
      val output = scala.io.Source.fromInputStream(process.getInputStream).mkString
      val errorOutput = scala.io.Source.fromInputStream(process.getErrorStream).mkString
      val exitCode = process.exitValue()

      println(s"Exit code: $exitCode")
      println(s"Output: $output")
      println(s"Error: $errorOutput")

      CompilationResult(exitCode, output, errorOutput)
    } else {
      process.destroy()
      throw new RuntimeException(s"Compiler process timed out after ${timeout.toSeconds} seconds")
    }
