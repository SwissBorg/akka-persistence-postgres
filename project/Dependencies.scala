import sbt._

object Dependencies {
  val Scala213 = "2.13.7"
  val ScalaVersions = Seq(Scala213)

  val AkkaVersion = "2.6.17"
  val AkkaBinaryVersion = "2.6"
  val FlywayVersion = "8.2.0"
  val ScaffeineVersion = "5.1.1"
  val ScalaTestVersion = "3.2.10"
  val SlickVersion = "3.3.3"
  val SlickPgVersion = "0.19.7"

  val LogbackVersion = "1.2.7"

  val JdbcDrivers = Seq("org.postgresql" % "postgresql" % "42.3.1")

  val Libraries: Seq[ModuleID] = Seq(
    "ch.qos.logback" % "logback-classic" % LogbackVersion % Test,
    "com.github.blemale" %% "scaffeine" % ScaffeineVersion,
    "com.github.tminglei" %% "slick-pg" % SlickPgVersion,
    "com.github.tminglei" %% "slick-pg_circe-json" % SlickPgVersion,
    "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion % Test,
    "com.typesafe.akka" %% "akka-persistence-tck" % AkkaVersion % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test,
    "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion,
    "com.typesafe.slick" %% "slick" % SlickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % SlickVersion,
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test) ++ JdbcDrivers.map(_ % Test)

  val Migration: Seq[ModuleID] = (Seq(
    "org.scalatest" %% "scalatest" % ScalaTestVersion,
    "com.typesafe.akka" %% "akka-testkit" % AkkaVersion,
    "ch.qos.logback" % "logback-classic" % LogbackVersion,
    "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
    "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
    "org.flywaydb" % "flyway-core" % FlywayVersion) ++ JdbcDrivers).map(_ % Test)
}
