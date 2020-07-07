import com.typesafe.tools.mima.plugin.MimaKeys.mimaBinaryIssueFilters

lazy val `akka-persistence-postgres` = project
  .in(file("."))
  .enablePlugins(ScalaUnidocPlugin)
  .disablePlugins(MimaPlugin)
  .aggregate(core)
  .settings(publish / skip := true)

lazy val core = project
  .in(file("core"))
  .enablePlugins(MimaPlugin, Publish)
  .settings(
    name := "akka-persistence-postgres",
    libraryDependencies ++= Dependencies.Libraries,
    mimaBinaryIssueFilters ++= Seq()
  )

TaskKey[Unit]("verifyCodeFmt") := {
  scalafmtCheckAll.all(ScopeFilter(inAnyProject)).result.value.toEither.left.foreach { _ =>
    throw new MessageOnlyException(
      "Unformatted Scala code found. Please run 'scalafmtAll' and commit the reformatted code")
  }
  (Compile / scalafmtSbtCheck).result.value.toEither.left.foreach { _ =>
    throw new MessageOnlyException(
      "Unformatted sbt code found. Please run 'scalafmtSbt' and commit the reformatted code")
  }
}

addCommandAlias("verifyCodeStyle", "headerCheck; verifyCodeFmt")
