import com.typesafe.tools.mima.plugin.MimaKeys.mimaBinaryIssueFilters

lazy val `akka-persistence-postgres` = project
  .in(file("."))
  .enablePlugins(ScalaUnidocPlugin)
  .disablePlugins(MimaPlugin, SitePlugin)
  .aggregate(core)
  .settings(publish / skip := true)

lazy val core = project
  .in(file("core"))
  .enablePlugins(MimaPlugin, Publish)
  .disablePlugins(SitePlugin)
  .settings(
    name := "akka-persistence-postgres",
    libraryDependencies ++= Dependencies.Libraries,
    mimaBinaryIssueFilters ++= Seq()
  )

//lazy val docs = project
//  .enablePlugins(ProjectAutoPlugin, AkkaParadoxPlugin, ParadoxSitePlugin, PreprocessPlugin, PublishRsyncPlugin)
//  .disablePlugins(MimaPlugin)
//  .settings(
//    name := "Akka Persistence Postgres",
//    publish / skip := true,
//    makeSite := makeSite.dependsOn(LocalRootProject / ScalaUnidoc / doc).value,
//    previewPath := (Paradox / siteSubdirName).value,
//    Preprocess / siteSubdirName := s"api/akka-persistence-postgres/${if (isSnapshot.value) "snapshot"
//      else version.value}",
//    Preprocess / sourceDirectory := (LocalRootProject / ScalaUnidoc / unidoc / target).value,
//    Paradox / siteSubdirName := s"docs/akka-persistence-postgres/${if (isSnapshot.value) "snapshot" else version.value}",
//    Compile / paradoxProperties ++= Map(
//        "project.url" -> "https://doc.akka.io/docs/akka-persistence-jdbc/current/",
//        "canonical.base_url" -> "https://doc.akka.io/docs/akka-persistence-jdbc/current",
//        "akka.version" -> Dependencies.AkkaVersion,
//        "slick.version" -> Dependencies.SlickVersion,
//        "extref.github.base_url" -> s"https://github.com/SwissBorg/akka-persistence-postgres/blob/${if (isSnapshot.value) "master"
//        else "v" + version.value}/%s",
//        // Slick
//        "extref.slick.base_url" -> s"https://scala-slick.org/doc/${Dependencies.SlickVersion}/%s",
//        // Akka
//        "extref.akka.base_url" -> s"https://doc.akka.io/docs/akka/${Dependencies.AkkaBinaryVersion}/%s",
//        "scaladoc.akka.base_url" -> s"https://doc.akka.io/api/akka/${Dependencies.AkkaBinaryVersion}/",
//        "javadoc.akka.base_url" -> s"https://doc.akka.io/japi/akka/${Dependencies.AkkaBinaryVersion}/",
//        "javadoc.akka.link_style" -> "direct",
//        // Java
//        "javadoc.base_url" -> "https://docs.oracle.com/javase/8/docs/api/",
//        // Scala
//        "scaladoc.scala.base_url" -> s"https://www.scala-lang.org/api/${scalaBinaryVersion.value}.x/",
//        "scaladoc.akka.persistence.jdbc.base_url" -> s"/${(Preprocess / siteSubdirName).value}/"),
//    paradoxGroups := Map("Language" -> Seq("Java", "Scala")),
//    resolvers += Resolver.jcenterRepo,
//    publishRsyncArtifacts += makeSite.value -> "www/",
//    publishRsyncHost := "akkarepo@gustav.akka.io",
//    apidocRootPackage := "akka")

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
