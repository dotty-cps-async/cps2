import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._

ThisBuild / scalaVersion := "3.8.0-RC6"
ThisBuild / organization := "io.github.dotty-cps-async"
ThisBuild / version := "0.1.0"

// Runtime library for CPS support (cross-platform)
lazy val library = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("library"))
  .settings(
    name := "cps2-library",
    scalacOptions += "-experimental",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % "1.0.4" % Test
    )
  )
  .jvmSettings(
    // JVM-specific settings
  )
  .jsSettings(
    // Scala.js-specific settings
  )
  //.nativeSettings(
  //  // Scala Native-specific settings
  //)

lazy val libraryJVM = library.jvm
lazy val libraryJS = library.js
//lazy val libraryNative = library.native

// Compiler plugin (JVM-only)
lazy val plugin = project
  .in(file("plugin"))
  .dependsOn(libraryJVM)
  .settings(
    name := "cps2-plugin",
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value % "provided",
      "org.scalameta" %% "munit" % "1.0.4" % Test
    ),
    Compile / packageBin / packageOptions += Package.ManifestAttributes(
      "Specification-Title" -> "Scala 3 Compiler Plugin",
      "Implementation-Title" -> "cps2-plugin"
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    Test / fork := true  // Fork tests to get proper classpath
  )

// Root project
lazy val root = project
  .in(file("."))
  .aggregate(libraryJVM, libraryJS, plugin)
  .settings(
    publish / skip := true
  )
