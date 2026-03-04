ThisBuild / organization := "io.github.cchantep"

// Format and style
ThisBuild / scalafmtOnCompile := true

lazy val rules = project
  .in(file("rules"))
  .settings(
    name := "offler-rules",
    description := "Good practices & coding convention Scalafix rules",
    libraryDependencies += "ch.epfl.scala" %% "scalafix-core" % _root_.scalafix.sbt.BuildInfo.scalafixVersion
  )

lazy val input = project
  .in(file("input"))
  .disablePlugins(ScalafixPlugin, ScalafmtPlugin)
  .settings(
    name := "input",
    description := "Input for tests about Scalafix rules",
    publish / skip := true,
    libraryDependencies += compilerPlugin(scalafixSemanticdb),
    scalacOptions ~= {
      _.filterNot(_ == "-Xfatal-warnings")
    }
  )

lazy val output = project
  .in(file("output"))
  .settings(
    name := "output",
    description := "Output for tests about Scalafix rules",
    scalafmtOnCompile := false,
    publish / skip := true,
    scalacOptions ~= {
      _.filterNot(_ == "-Xfatal-warnings")
    }
  )

lazy val tests = project
  .in(file("tests"))
  .settings(
    publish / skip := true,
    libraryDependencies += "ch.epfl.scala" %% "scalafix-core" % _root_.scalafix.sbt.BuildInfo.scalafixVersion,
    Compile / compile := (Compile / compile).dependsOn(input / Compile / compile).value,
    scalafixTestkitOutputSourceDirectories := (output / Compile / sourceDirectories).value,
    scalafixTestkitInputSourceDirectories := (input / Compile / sourceDirectories).value,
    scalafixTestkitInputClasspath := (input / Compile / fullClasspath).value,
    Test / dependencyClasspath += (input / Compile / target).value / "scala-2.13" / "meta"
  )
  .dependsOn(rules, output)
  .enablePlugins(ScalafixTestkitPlugin)

lazy val root = (project in file("."))
  .settings(
    name := "offler",
    publish := ({}),
    publishTo := None,
    publish / skip := true
  ).aggregate(rules, tests)
