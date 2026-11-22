ThisBuild / scalaVersion := "3.9.0"
// scalactive 0.7 が .st から作るコードは、Scala 3.9 で警告が出る（先頭の import の `X as _` と、implicit の渡し方）。
// scalactive を直すまで、生成したファイルのこの 2 種類だけを黙らせる
ThisBuild / scalacOptions ++= Seq(
  "-Wconf:src=.*/src_managed/main/scalactive/.*&msg=unused import:s",
  "-Wconf:src=.*/src_managed/main/scalactive/.*&msg=Implicit parameters should be provided with a `using` clause:s"
)
ThisBuild / organization := "io.github.ondeoma"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / licenses := List("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / homepage := Some(url("https://github.com/Ondeoma/stui"))
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:imports", "-no-indent")

val http4sV = "1.0.0-M49"
val circeV = "0.14.16"
val doobieV = "1.0.0-RC12"
val ceV = "3.7.1"
val fs2V = "3.14.0"
val munitV = "1.3.6"
val flywayV = "11.3.0"
val otel4sV = "1.2.0"
val otelV = "1.68.0"
val otelInstV = "2.33.1-alpha"

// sbirds シリーズの共通ライブラリ（GitHub のリポジトリを直接参照する. 手元の sbirds-lib で試すときは SBIRDS_LIB=../sbirds-lib）
lazy val sbirdsLib = sys.env
  .get("SBIRDS_LIB")
  .map(p => file(p).getCanonicalFile.toURI)
  .getOrElse(uri("https://github.com/Ondeoma/sbirds-lib.git#v0.5.0"))
lazy val libSharedJVM = ProjectRef(sbirdsLib, "sharedJVM")
lazy val libSharedJS = ProjectRef(sbirdsLib, "sharedJS")
lazy val libObserve = ProjectRef(sbirdsLib, "observe")
lazy val libAuth = ProjectRef(sbirdsLib, "auth")
lazy val libHttp = ProjectRef(sbirdsLib, "http")
lazy val libMcp = ProjectRef(sbirdsLib, "mcp")
lazy val libPush = ProjectRef(sbirdsLib, "push")
lazy val libWeb = ProjectRef(sbirdsLib, "web")
lazy val libMail = ProjectRef(sbirdsLib, "mail")
lazy val libStorage = ProjectRef(sbirdsLib, "storage")
lazy val libLinks = ProjectRef(sbirdsLib, "links")
lazy val libDb = ProjectRef(sbirdsLib, "db")
lazy val libNet = ProjectRef(sbirdsLib, "net")
lazy val libOauth = ProjectRef(sbirdsLib, "oauth")

lazy val commonTest = Seq(
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit" % munitV % Test,
    "org.typelevel" %% "munit-cats-effect" % "2.2.1" % Test
  ),
  Test / fork := true
)

// バックエンドと画面(Scala.js)で共有する API の型
lazy val shared = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full) // 日時(Timestamp)だけ JVM と JS で実装が違う
  .in(file("modules/shared"))
  .jvmConfigure(_.dependsOn(libSharedJVM))
  .jsConfigure(_.dependsOn(libSharedJS))
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core" % circeV,
      "io.circe" %%% "circe-parser" % circeV
    )
  )

// 画面: Scalactive の SPA（frontend/ の Vite から読み込む）
lazy val web = (project in file("modules/web"))
  .enablePlugins(ScalaJSPlugin, ScalactivePlugin)
  .dependsOn(shared.js, libWeb)
  .settings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "io.github.ondeoma" %%% "scalactive" % "0.7.0"
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
  .dependsOn(core, libObserve, libDb)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "doobie-core" % doobieV,
      "org.tpolecat" %% "doobie-hikari" % doobieV,
      "org.tpolecat" %% "doobie-postgres" % doobieV,
      "org.tpolecat" %% "doobie-postgres-circe" % doobieV,
      "org.typelevel" %% "cats-effect" % ceV,
      "org.postgresql" % "postgresql" % "42.7.14",
      "org.flywaydb" % "flyway-core" % flywayV,
      "org.flywaydb" % "flyway-database-postgresql" % flywayV % Runtime
    )
  )

lazy val auth = (project in file("modules/auth"))
  .dependsOn(core, libAuth)
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-client" % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV
    )
  )

lazy val api = (project in file("modules/api"))
  .dependsOn(
    core,
    db % "compile;test->test",
    auth,
    shared.jvm,
    libObserve,
    libHttp,
    libMcp,
    libPush,
    libMail,
    libStorage,
    libLinks,
    libNet,
    libOauth
  )
  .settings(commonTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % http4sV,
      "org.http4s" %% "http4s-circe" % http4sV,
      "org.http4s" %% "http4s-server" % http4sV
    )
  )

lazy val app = (project in file("modules/app"))
  .dependsOn(api)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sV,
      "org.http4s" %% "http4s-ember-client" % http4sV,
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      "org.typelevel" %% "log4cats-slf4j" % "2.8.0"
    ),
    Compile / mainClass := Some("stui.app.Main"),
    fork := true
  )

lazy val root = (project in file("."))
  .aggregate(shared.jvm, shared.js, web, core, db, auth, api, app)
  .settings(name := "stui", publish / skip := true)

// JDK 25 以上でビルドする（本番も JDK 25 で動かす）
Global / onLoad := (Global / onLoad).value.andThen { s =>
  val v = java.lang.Runtime.version.feature
  if (v < 25) sys.error(s"JDK 25 以上が必要です（いまは JDK $v）")
  s
}
