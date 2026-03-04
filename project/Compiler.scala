import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin

object Compiler extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = JvmPlugin

  val scala212 = "2.12.21"
  val scala213 = "2.13.18"

  override def projectSettings = Seq(
    scalaVersion := scala213,
    crossScalaVersions := Seq(scala212, scala213),
    scalacOptions ++= Seq(
      "-encoding",
      "UTF-8",
      "-unchecked",
      "-deprecation",
      "-feature",
      "-language:higherKinds"
    ),
    scalacOptions ++= {
      if (scalaBinaryVersion.value == "2.11") {
        Seq.empty[String]
      } else {
        Seq("-Xfatal-warnings")
      }
    },
    scalacOptions ++= {
      if (scalaBinaryVersion.value startsWith "2.") {
        Seq(
          "-Xlint",
          "-g:vars"
        )
      } else Seq.empty
    },
    scalacOptions ++= {
      val sv = scalaBinaryVersion.value

      if (sv == "2.12") {
        Seq(
          "-target:jvm-1.8",
          "-Xmax-classfile-name",
          "128",
          "-Ywarn-numeric-widen",
          "-Ywarn-dead-code",
          "-Ywarn-value-discard",
          "-Ywarn-infer-any",
          "-Ywarn-unused",
          "-Ywarn-unused-import",
          "-Xlint:missing-interpolator",
          "-Ywarn-macros:after"
        )
      } else {
        Seq(
          "-release",
          "8",
          "-explaintypes",
          "-Werror",
          "-Wnumeric-widen",
          "-Wdead-code",
          "-Wvalue-discard",
          "-Wextra-implicit",
          "-Wmacros:after",
          "-Wunused"
        )
      }
    },
    Test / scalacOptions ~= {
      _.filterNot(_ == "-Werror")
    },
    Test / scalacOptions += "-Xlint:-infer-any", // specs2 `and`
    Compile / console / scalacOptions ~= {
      _.filterNot { opt =>
        opt.startsWith("-P") || opt.startsWith("-X") || opt.startsWith("-W")
      }
    },
    Test / console / scalacOptions ~= {
      _.filterNot { opt =>
        opt.startsWith("-P") || opt.startsWith("-X") || opt.startsWith("-W")
      }
    },
  )
}
