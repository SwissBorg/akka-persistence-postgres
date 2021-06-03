import sbt._

object Dependencies {
  val Scala212 = "2.12.13"
  val Scala213 = "2.13.5"
  val ScalaVersions = Seq(Scala212, Scala213)

  val AkkaVersion = "2.6.14"
  val AkkaBinaryVersion = "2.6"

  val SlickVersion = "3.3.3"
  val ScalaTestVersion = "3.2.8"
  val SlickPgVersion = "0.19.5"

  val ScaffeineVersion = "4.0.2"

  val LogbackVersion = "1.2.3"

  val JdbcDrivers = Seq("org.postgresql" % "postgresql" % "42.2.19")

  val Libraries: Seq[ModuleID] = Seq(
    "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion,
    "com.typesafe.slick" %% "slick" % SlickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % SlickVersion,
    "com.github.tminglei" %% "slick-pg" % SlickPgVersion,
    "com.github.tminglei" %% "slick-pg_circe-json" % SlickPgVersion,
    "com.github.blemale" %% "scaffeine" % ScaffeineVersion,
    "ch.qos.logback" % "logback-classic" % LogbackVersion % Test,
    "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion % Test,
    "com.typesafe.akka" %% "akka-persistence-tck" % AkkaVersion % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test,
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test) ++ JdbcDrivers.map(_ % Test)

  val Migration: Seq[ModuleID] = (Seq(
    "org.scalatest" %% "scalatest" % ScalaTestVersion,
    "com.typesafe.akka" %% "akka-testkit" % AkkaVersion,
    "ch.qos.logback" % "logback-classic" % LogbackVersion,
    "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
    "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
    "org.flywaydb" % "flyway-core" % "7.9.0") ++ JdbcDrivers).map(_ % Test)
}
