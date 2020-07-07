import de.heikoseeberger.sbtheader.HeaderPlugin
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport.{ headerLicense, HeaderLicense }
import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin

object ProjectAutoPlugin extends AutoPlugin {
  object autoImport {}

  override val requires = JvmPlugin && HeaderPlugin

  override def globalSettings =
    Seq(
      organization := "com.swissborg",
      organizationName := "SwissBorg",
      organizationHomepage := None,
      homepage := Some(url("https://github.com/SwissBorg/akka-persistence-postgres")),
      scmInfo := Some(
          ScmInfo(
            url("https://github.com/SwissBorg/akka-persistence-postgres"),
            "git@github.com:SwissBorg/akka-persistence-postgres.git")),
      licenses := Seq("Apache-2.0" -> url("https://opensource.org/licenses/Apache-2.0")),
      description := "A plugin for storing events in a PostgreSQL journal",
      startYear := Some(2020))

  override val trigger: PluginTrigger = allRequirements

  override val projectSettings: Seq[Setting[_]] = Seq(
    crossVersion := CrossVersion.binary,
    crossScalaVersions := Dependencies.ScalaVersions,
    scalaVersion := Dependencies.Scala212,
    Test / fork := true,
    Test / parallelExecution := false,
    Test / logBuffered := true,
    scalacOptions ++= Seq(
        "-encoding",
        "UTF-8",
        "-deprecation",
        "-feature",
        "-unchecked",
        "-Xlog-reflective-calls",
        "-language:higherKinds",
        "-language:implicitConversions",
        "-target:jvm-1.8"),
    scalacOptions += {
      if (scalaVersion.value.startsWith("2.13")) ""
      else "-Ypartial-unification"
    },
    scalacOptions += "-Ydelambdafy:method",
    Compile / doc / scalacOptions := scalacOptions.value ++ Seq(
        "-doc-title",
        "Akka Persistence Postgres",
        "-doc-version",
        version.value,
        "-sourcepath",
        (baseDirectory in ThisBuild).value.toString,
        "-skip-packages",
        "akka.pattern", // for some reason Scaladoc creates this
        "-doc-source-url", {
          val branch = if (isSnapshot.value) "master" else s"v${version.value}"
          s"https://github.com/SwissBorg/akka-persistence-postgres/tree/${branch}€{FILE_PATH_EXT}#L€{FILE_LINE}"
        }),
    // show full stack traces and test case durations
    Test / testOptions += Tests.Argument("-oDF"),
    headerLicense := Some(HeaderLicense.Custom("""|Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
           |Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
           |""".stripMargin)))

}
