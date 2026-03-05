import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

object Publish extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = JvmPlugin

  private val repoName = env("PUBLISH_REPO_NAME")
  private val repoUrl = env("PUBLISH_REPO_URL")

  override lazy val projectSettings = Seq(
    licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT")),
    publishMavenStyle := true,
    Test / publishArtifact := false,
    pomIncludeRepository := { _ => false },
    publishTo := Option(repoName).map(_ at repoUrl),
    credentials += Credentials(
      repoName,
      env("PUBLISH_REPO_ID"),
      env("PUBLISH_USER"),
      env("PUBLISH_PASS")
    ),
    homepage := Some(url("https://github.com/cchantep/offler/")),
    autoAPIMappings := true,
    pomExtra :=
      <scm>
        <url>git@github.com:cchantep/offler.git</url>
        <connection>scm:git:git@github.com:cchantep/offler.git</connection>
      </scm>
      <developers>
        <developer>
          <id>cchantep</id>
          <name>cchantep</name>
          <url>https://github.com/cchantep</url>
        </developer>
      </developers>
  )

  @inline private def env(n: String): String = sys.env.get(n).getOrElse(n)
}
