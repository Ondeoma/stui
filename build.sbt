ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "io.github.ondeoma"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / licenses     := List("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / homepage     := Some(url("https://github.com/Ondeoma/stui"))
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:imports")

val http4sV   = "1.0.0-M49"
val circeV    = "0.14.16"
val doobieV   = "1.0.0-RC12"
val ceV       = "3.7.1"
val fs2V      = "3.14.0"
val munitV    = "1.3.6"
val awsV      = "2.30.38"
val flywayV   = "11.3.0"

lazy val commonTest = Seq(
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit"             % munitV  % Test,
    "org.typelevel" %% "munit-cats-effect" % "2.2.1" % Test
  ),
  Test / fork := true
)

// バックエンドと画面(Scala.js)で共有する API の型
lazy val shared = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full) // 日時(Timestamp)だけ JVM と JS で実装が違う
  .in(file("modules/shared"))
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core"   % circeV,
      "io.circe" %%% "circe-parser" % circeV
    )
  )

// 画面: Scalactive の SPA（frontend/ の Vite から読み込む）
lazy val web = (project in file("modules/web"))
  .enablePlugins(ScalaJSPlugin, ScalactivePlugin)
  .dependsOn(shared.js)
  .settings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "io.github.ondeoma" %%% "scalactive" % "0.6.2"
    )
  )

// ドメイン（純粋なロジック）
lazy val core = (project in file("modules/core"))
  .dependsOn(shared.jvm)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-parser" % circeV
    )
  )

lazy val db = (project in file("modules/db"))
  .dependsOn(core)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.tpolecat"  %% "doobie-core"     % doobieV,
      "org.tpolecat"  %% "doobie-hikari"   % doobieV,
      "org.tpolecat"  %% "doobie-postgres" % doobieV,
      "org.tpolecat"  %% "doobie-postgres-circe" % doobieV,
      "org.typelevel" %% "cats-effect"     % ceV,
      "org.postgresql" % "postgresql"      % "42.7.14",
      "org.flywaydb"   % "flyway-core"     % flywayV,
      "org.flywaydb"   % "flyway-database-postgresql" % flywayV % Runtime
    )
  )

lazy val auth = (project in file("modules/auth"))
  .dependsOn(core)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-client" % http4sV,
      "org.http4s" %% "http4s-circe"  % http4sV
    )
  )

lazy val storage = (project in file("modules/storage"))
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      // netty-nio-client は依存ごとに外す（プロジェクト全体で外すと下流の解決で AWS SDK 自体が落ちることがある）
      ("software.amazon.awssdk" % "s3"            % awsV).exclude("software.amazon.awssdk", "netty-nio-client"),
      "software.amazon.awssdk"  % "apache-client" % awsV,
      "co.fs2"                 %% "fs2-io"        % fs2V,
      "org.typelevel"          %% "cats-effect"   % ceV
    )
  )

lazy val notifier = (project in file("modules/notify"))
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.eclipse.angus" % "angus-mail"  % "2.0.3",
      "org.typelevel"    %% "cats-effect" % ceV
    )
  )

lazy val api = (project in file("modules/api"))
  .dependsOn(core, db % "compile;test->test", auth, storage, notifier, shared.jvm)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl"    % http4sV,
      "org.http4s" %% "http4s-circe"  % http4sV,
      "org.http4s" %% "http4s-server" % http4sV
    )
  )

lazy val app = (project in file("modules/app"))
  .dependsOn(api)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s"    %% "http4s-ember-server" % http4sV,
      "org.http4s"    %% "http4s-ember-client" % http4sV,
      "ch.qos.logback" % "logback-classic"     % "1.5.18",
      "org.typelevel" %% "log4cats-slf4j"      % "2.8.0"
    ),
    Compile / mainClass := Some("stui.app.Main"),
    fork := true
  )

lazy val root = (project in file("."))
  .aggregate(shared.jvm, shared.js, web, core, db, auth, storage, notifier, api, app)
  .settings(name := "stui", publish / skip := true)
