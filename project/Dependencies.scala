import sbt._

object Dependencies {
  val Scala213 = "2.13.8"
  val ScalaVersions = Seq(Scala213)

  val AkkaVersion = "2.6.16"
  val FlywayVersion = "8.5.11"
  val ScaffeineVersion = "5.2.1"
  val ScalaTestVersion = "3.2.12"
  val SlickVersion = "3.3.3"
  val SlickPgVersion = "0.20.3"

  val LogbackVersion = "1.2.11"

  val JdbcDrivers = Seq("org.postgresql" % "postgresql" % "42.3.6")

  val Libraries: Seq[ModuleID] = Seq(
    "ch.qos.logback" % "logback-classic" % LogbackVersion % Test,
    "com.github.blemale" %% "scaffeine" % ScaffeineVersion,
    "com.github.tminglei" %% "slick-pg" % SlickPgVersion,
    "com.github.tminglei" %% "slick-pg_circe-json" % SlickPgVersion,
    "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion % Test,
    "com.typesafe.akka" %% "akka-persistence-tck" % AkkaVersion % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test,
    "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion % Provided,
    "com.typesafe.slick" %% "slick" % SlickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % SlickVersion,
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test) ++ JdbcDrivers.map(_ % Test)

  val Migration: Seq[ModuleID] =
    Seq("com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion).map(_ % Compile) ++ (Seq(
      "org.scalatest" %% "scalatest" % ScalaTestVersion,
      "com.typesafe.akka" %% "akka-testkit" % AkkaVersion,
      "ch.qos.logback" % "logback-classic" % LogbackVersion,
      "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
      "org.flywaydb" % "flyway-core" % FlywayVersion) ++ JdbcDrivers).map(_ % Test)
}
