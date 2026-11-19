package stui.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{ACursor, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{`WWW-Authenticate`, Allow, Authorization}
import stui.core.*
import stui.shared.*

/**
 * MCP（Model Context Protocol）のサーバー。POST /mcp に JSON-RPC を送る（Streamable HTTP。応答は JSON、SSE は使わない）。
 * 認証は APIキー（Bearer）だけ。各ツールは REST API をそのキーで内部から呼ぶので、権限・スコープ・監査は REST API と同じになる。
 */
final class McpRoutes(api: HttpRoutes[IO], sessions: SessionAuth, publicUrl: String) extends Http4sDsl[IO] {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "mcp" => postMcp(req)
    case (GET | DELETE) -> Root / "mcp" => mcpMethodNotAllowed()
    case GET -> Root / "api" / "endpoints" => getEndpoints()
  }

  private def postMcp(req: Request[IO]): IO[Response[IO]] =
    req.headers.get[Authorization] match {
      case Some(auth @ Authorization(Credentials.Token(AuthScheme.Bearer, _))) =>
        sessions.fromRequest(Request[IO](Method.GET, Uri.unsafeFromString("/mcp")).putHeaders(auth)).flatMap {
          case None => IO.pure(unauthorized)
          case Some(session) =>
            val call = Caller(auth)
            req.as[Json].attempt.flatMap {
              case Left(_) => BadRequest(rpcFail(Json.Null, -32700, "parse error"))
              case Right(j) =>
                j.asArray match {
                  case Some(batch) if batch.size > 50 => BadRequest(rpcFail(Json.Null, -32600, "バッチは50件までです"))
                  case Some(batch) => batch.toList.traverse(handle(call, session, _)).map(_.flatten).flatMap(rs =>
                      if rs.isEmpty then Accepted() else Ok(rs.asJson)
                    )
                  case None => handle(call, session, j).flatMap(_.fold(Accepted())(Ok(_)))
                }
            }
        }
      case _ => IO.pure(unauthorized)
    }

  private def mcpMethodNotAllowed(): IO[Response[IO]] =
    MethodNotAllowed(Allow(Method.POST))

  private def getEndpoints(): IO[Response[IO]] =
    Ok(ApiCatalog.all.map(e =>
      Json.obj("method" -> e.method.asJson, "path" -> e.path.asJson, "description" -> e.description.asJson)
    ))

  private val base = publicUrl.stripSuffix("/")
  private val Versions = List("2025-06-18", "2025-03-26", "2024-11-05")

  // ------------------------------------------------------------------ REST API の内部呼び出し

  private final class Caller(auth: Authorization) {
    def apply(method: Method, uri: Uri, body: Option[Json] = None): IO[Either[String, Json]] = {
      val req0 = Request[IO](method, uri).putHeaders(auth, "X-Stui-Mcp" -> "1")
      val req = body.fold(req0)(req0.withEntity(_))
      api.run(req).value.flatMap {
        case None => IO.pure(Left("404 見つかりません"))
        case Some(r) =>
          r.bodyText.compile.string.map { s =>
            val j = if s.isEmpty then Json.Null else parse(s).getOrElse(Json.fromString(s))
            if r.status.isSuccess then Right(j)
            else Left(s"${r.status.code} ${j.hcursor.get[String]("error").getOrElse(r.status.reason)}")
          }
      }
    }
  }

  private def path(segments: String*): Uri =
    Uri(path = Uri.Path(segments.toVector.map(Uri.Path.Segment(_)), absolute = true))

  // ------------------------------------------------------------------ ツール

  private type Result = IO[Either[String, String]]

  private final case class Tool(name: String,
                                description: String,
                                schema: Json,
                                readOnly: Boolean,
                                destructive: Boolean = false)(val run: (Caller, ACursor) => Result) {
    def json: Json = Json.obj(
      "name" -> name.asJson,
      "description" -> description.asJson,
      "inputSchema" -> schema,
      "annotations" -> Json.obj(
        "readOnlyHint" -> readOnly.asJson,
        "destructiveHint" -> destructive.asJson,
        "openWorldHint" -> false.asJson
      )
    )
  }

  private def schema(required: List[String], props: (String, Json)*): Json =
    Json.obj("type" -> "object".asJson, "properties" -> Json.obj(props*), "required" -> required.asJson)
  private def str(d: String): Json = Json.obj("type" -> "string".asJson, "description" -> d.asJson)
  private def int(d: String): Json = Json.obj("type" -> "integer".asJson, "description" -> d.asJson)
  private def strs(d: String): Json =
    Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson), "description" -> d.asJson)
  private def obj(d: String): Json = Json.obj("type" -> "object".asJson, "description" -> d.asJson)

  private def need(a: ACursor, k: String): Either[String, String] =
    a.get[String](k).toOption.map(_.trim).filter(_.nonEmpty).toRight(s"$k を指定してください")
  private def opt(a: ACursor, k: String): Option[String] = a.get[String](k).toOption.map(_.trim)
  private def s(j: Json, k: String): String = j.hcursor.get[String](k).getOrElse("")

  extension [A](e: Either[String, A]) {
    private def withArgs(f: A => Result): Result = e.fold(err => IO.pure(Left(err)), f)
  }
  extension (r: IO[Either[String, Json]]) {
    private def asText: Result = r.map(_.map(_.spaces2))
  }

  /** ユーザーの指定（me・名前・メール・ID）を ID にする。 */
  private def userId(call: Caller, v: String): IO[Either[String, String]] =
    v.trim.stripPrefix("@") match {
      case "me" | "自分" => call(Method.GET, path("api", "me")).map(_.map(j => s(j, "id")))
      case x if uuidOpt(x).isDefined => IO.pure(Right(x))
      case x => call(Method.GET, path("api", "users").withQueryParam("q", x)).map(_.flatMap(j =>
          j.asArray.toList.flatten.headOption.map(u => s(u, "id")).toRight(s"ユーザー「$x」が見つかりません")
        ))
    }

  /** チャンネルの指定（ID・名前・#名前・@名前 は DM）を ID にする。 */
  private def channelId(call: Caller, v: String): IO[Either[String, String]] =
    v.trim match {
      case x if uuidOpt(x).isDefined => IO.pure(Right(x))
      case x if x.startsWith("@") =>
        x.split(',').toList.map(_.trim).filter(_.nonEmpty).traverse(userId(call, _)).map(_.sequence).flatMap {
          case Left(e) => IO.pure(Left(e))
          // 既にある DM を探し、なければ開く（参照専用のキーでも既にある DM は読める）
          case Right(ids) =>
            call(Method.GET, path("api", "dms").withQueryParam("userIds", ids.mkString(","))).flatMap {
              case Right(j) => IO.pure(Right(s(j, "id")))
              case Left(_) => call(Method.POST, path("api", "dms"), Some(Json.obj("userIds" -> ids.asJson))).map(_.map(
                  j => s(j, "id")
                ))
            }
        }
      case x => call(Method.GET, path("api", "channels", "by-name", x.stripPrefix("#"))).map(_.map(j => s(j, "id")))
    }

  private def plain(m: Json): String =
    RichText.toPlain(m.hcursor.downField("body").focus.getOrElse(Json.Null)).replace("\n", " ").take(1000)

  /** メッセージ1件を1行に（[日時] 名前: 本文（id・返信数））。 */
  private def line(m: Json): String = {
    val c = m.hcursor
    val name = c.downField(
      "props"
    ).get[String]("username").toOption.orElse(c.downField("author").get[String]("name").toOption).getOrElse("（不明）")
    val replies = c.get[Int]("replyCount").getOrElse(0)
    val files = c.downField("attachments").as[List[Json]].getOrElse(Nil).map(f => s(f, "fileName"))
    val reacts = c.downField("reactions").as[List[Json]].getOrElse(Nil).map(r =>
      s"${s(r, "emoji")}${r.hcursor.downField("users").as[List[Json]].map(_.size).getOrElse(0)}"
    )
    List(
      Some(s"[${s(m, "createdAt").take(16).replace('T', ' ')}] $name: ${
          if c.get[Boolean]("deleted").contains(true) then "（削除されました）" else plain(m)
        }"),
      Option.when(files.nonEmpty)(s"添付: ${files.mkString(", ")}"),
      Option.when(reacts.nonEmpty)(reacts.mkString(" ")),
      Option.when(replies > 0)(s"返信 $replies 件"),
      Some(s"id=${s(m, "id")}")
    ).flatten.mkString("  ")
  }

  private val tools: List[Tool] = List(
    Tool("get_me", "自分（この APIキーの持ち主）の情報とキーのスコープ。", schema(Nil), readOnly = true)((call, _) =>
      call(Method.GET, path("api", "me")).asText
    ),
    Tool(
      "list_channels",
      "参加しているチャンネル・DM（未読・メンションの数つき）。all を true にすると参加できる公開チャンネルも出す。",
      schema(Nil, "all" -> Json.obj("type" -> "boolean".asJson, "description" -> "参加できるチャンネルも出す".asJson)),
      readOnly = true
    ) {
      (call, a) =>
        val u =
          if a.get[Boolean]("all").contains(true) then path("api", "channels", "browse") else path("api", "channels")
        call(Method.GET, u).map(_.map(j =>
          j.asArray.toList.flatten.map { c =>
            val cc = c.hcursor
            val name = if Set("dm", "group").contains(s(c, "kind")) then
              "@" + cc.downField("dmUsers").as[List[Json]].getOrElse(Nil).map(u => s(u, "name")).mkString(", ")
            else "#" + s(c, "name")
            val unread = cc.get[Int]("unread").getOrElse(0)
            val mention = cc.get[Int]("mentions").getOrElse(0)
            val member = if cc.get[Boolean]("member").contains(true) then "" else "（未参加）"
            s"- $name$member 未読 $unread・メンション $mention ${s(c, "topic")} id=${s(c, "id")}"
          }.mkString("\n")
        ))
    },
    Tool(
      "read_channel",
      "チャンネル・DM のメッセージを読む（新しいものから limit 件を古い順に）。channel は名前・#名前・@ユーザー名（DM）・ID。",
      schema(
        List("channel"),
        "channel" -> str("チャンネル"),
        "limit" -> int("件数（既定30、最大200）"),
        "before" -> int("この seq より前（さかのぼるとき）")
      ),
      readOnly = true
    ) {
      (call, a) =>
        need(a, "channel").withArgs(ch =>
          channelId(call, ch).flatMap {
            case Left(e) => IO.pure(Left(e))
            case Right(id) =>
              val u0 =
                path("api", "channels", id, "messages").withQueryParam("limit", a.get[Int]("limit").getOrElse(30))
              val u = a.get[Long]("before").toOption.fold(u0)(b => u0.withQueryParam("before", b))
              call(Method.GET, u).map(_.map { p =>
                val ms = p.hcursor.downField("messages").as[List[Json]].getOrElse(Nil)
                val more = if p.hcursor.get[Boolean]("hasMore").contains(true) then
                  s"\n（さらに古いものは before=${ms.headOption.map(m => m.hcursor.get[Long]("seq").getOrElse(0L)).getOrElse(0L)}）"
                else ""
                (if ms.isEmpty then "（メッセージはありません）" else ms.map(line).mkString("\n")) + more
              })
          }
        )
    },
    Tool(
      "read_thread",
      "スレッド（親と返信）を読む。",
      schema(List("messageId"), "messageId" -> str("親または返信のメッセージ ID")),
      readOnly = true
    ) {
      (call, a) =>
        need(a, "messageId").withArgs(id =>
          call(Method.GET, path("api", "messages", id, "thread")).map(_.map { t =>
            val c = t.hcursor
            (c.downField("root").focus.map(line).toList ++
              c.downField("replies").as[List[Json]].getOrElse(Nil).map("  ↳ " + line(_))).mkString("\n")
          })
        )
    },
    Tool(
      "post_message",
      "チャンネル・DM に投稿する（Markdown）。threadId を付けるとスレッドに返信する。channel は名前・#名前・@ユーザー名（DM。カンマ区切りでグループ DM）・ID。",
      schema(
        List("channel", "text"),
        "channel" -> str("チャンネル"),
        "text" -> str("本文（Markdown）"),
        "threadId" -> str("返信するスレッドの親メッセージ ID")
      ),
      readOnly = false
    ) {
      (call, a) =>
        (need(a, "channel"), need(a, "text")).tupled.withArgs((ch, t) =>
          channelId(call, ch).flatMap {
            case Left(e) => IO.pure(Left(e))
            case Right(id) =>
              call(
                Method.POST,
                path("api", "channels", id, "messages"),
                Some(Json.obj("body" -> t.asJson, "rootId" -> opt(a, "threadId").filter(_.nonEmpty).asJson))
              )
                .map(_.map(m => s"投稿しました: $base/m/${s(m, "id")}"))
          }
        )
    },
    Tool(
      "add_reaction",
      "メッセージにリアクションする。",
      schema(List("messageId", "emoji"), "messageId" -> str("メッセージ ID"), "emoji" -> str("絵文字（👍 など）")),
      readOnly = false
    ) {
      (call, a) =>
        (need(a, "messageId"), need(a, "emoji")).tupled.withArgs((id, e) =>
          call(
            Method.POST,
            path("api", "messages", id, "reactions"),
            Some(Json.obj("emoji" -> e.asJson))
          ).map(_.map(_ => s"$e を付けました"))
        )
    },
    Tool(
      "search_messages",
      "メッセージを検索する（日本語の部分一致・かなの揺れ・タイプミスを許す）。絞り込み: in:#チャンネル from:@名前 with:@名前 has:link|file|image|reaction|pin|meeting is:thread|pinned|saved|dm before:2026-11-01 after:-7d on:today during:2026-11 order:new|old -除く語 \"語句\"",
      schema(List("query"), "query" -> str("検索の条件"), "limit" -> int("件数（既定30）")),
      readOnly = true
    ) {
      (call, a) =>
        need(a, "query").withArgs(q =>
          call(
            Method.GET,
            path("api", "search").withQueryParam("q", q).withQueryParam("limit", a.get[Int]("limit").getOrElse(30))
          ).map(_.flatMap { j =>
            j.hcursor.get[Option[String]]("error").toOption.flatten match {
              case Some(e) => Left(e)
              case None =>
                val hits = j.hcursor.downField("hits").as[List[Json]].getOrElse(Nil)
                Right((s"${j.hcursor.get[Int]("total").getOrElse(0)} 件" :: hits.map { h =>
                  val c = h.hcursor.downField("channel")
                  val ch = if Set("dm", "group").contains(c.get[String]("kind").getOrElse("")) then "DM"
                  else "#" + c.get[String]("name").getOrElse("")
                  s"$ch ${h.hcursor.downField("message").focus.map(line).getOrElse("")}"
                }).mkString("\n"))
            }
          })
        )
    },
    Tool("list_threads", "フォロー中のスレッド（未読の返信の数つき）。", schema(Nil), readOnly = true) {
      (call, _) =>
        call(Method.GET, path("api", "threads")).map(_.map(j =>
          j.asArray.toList.flatten.map { t =>
            val c = t.hcursor
            s"#${c.downField("channel").get[String]("name").getOrElse("")} 未読 ${c.get[Int]("unread").getOrElse(0)}: ${c.downField("root").focus.map(line).getOrElse("")}"
          }.mkString("\n")
        ))
    },
    Tool(
      "set_reminder",
      "リマインダーを作る（その時刻に stui から DM で知らせる）。at は ISO 8601（2026-11-20T09:00:00+09:00）。",
      schema(List("at"), "at" -> str("日時（ISO 8601）"), "text" -> str("内容"), "messageId" -> str("このメッセージについて知らせる")),
      readOnly = false
    ) {
      (call, a) =>
        need(a, "at").withArgs(at =>
          scala.util.Try(java.time.OffsetDateTime.parse(at).toInstant).toOption
            .orElse(scala.util.Try(java.time.Instant.parse(at)).toOption) match {
            case None => IO.pure(Left("at は ISO 8601（2026-11-20T09:00:00+09:00）で指定してください"))
            case Some(i) =>
              call(
                Method.POST,
                path("api", "reminders"),
                Some(Json.obj(
                  "text" -> opt(a, "text").asJson,
                  "messageId" -> opt(a, "messageId").filter(_.nonEmpty).asJson,
                  "remindAt" -> i.asJson
                ))
              ).map(_.map(_ => s"$at にリマインダーを設定しました"))
          }
        )
    },
    Tool(
      "create_meeting",
      "skaka（Web 会議）に会議室を作り、チャンネルに案内を投稿する（skaka と連携している必要がある）。",
      schema(List("channel"), "channel" -> str("チャンネル"), "name" -> str("会議の名前")),
      readOnly = false
    ) {
      (call, a) =>
        need(a, "channel").withArgs(ch =>
          channelId(call, ch).flatMap {
            case Left(e) => IO.pure(Left(e))
            case Right(id) => call(
                Method.POST,
                path("api", "channels", id, "meeting"),
                Some(Json.obj("name" -> opt(a, "name").asJson))
              ).map(_.map(m =>
                s"会議室を作りました: ${m.hcursor.downField("props").downField("meeting").get[String]("url").getOrElse("")}"
              ))
          }
        )
    },
    Tool(
      "list_notifications",
      "自分への通知（未読・種類で絞れる）。",
      schema(
        Nil,
        "kinds" -> str("種類（mention,dm,thread,channel,reminder,app のカンマ区切り）"),
        "unreadOnly" -> Json.obj("type" -> "boolean".asJson)
      ),
      readOnly = true
    ) {
      (call, a) =>
        val u =
          List("kinds" -> opt(a, "kinds"), "unread" -> a.get[Boolean]("unreadOnly").toOption.map(_.toString)).collect {
            case (k, Some(v)) => k -> v
          }
            .foldLeft(path("api", "notifications"))((u, kv) => u.withQueryParam(kv._1, kv._2))
        call(Method.GET, u).asText
    },
    Tool(
      "list_api_endpoints",
      "REST API の一覧。専用のツールがない操作（チャンネルの作成・ピン留め・定期メッセージなど）は call_api で行える。",
      schema(Nil),
      readOnly = true
    ) {
      (_, _) =>
        IO.pure(Right(ApiCatalog.all.map(e => s"${e.method} ${e.path} — ${e.description}").mkString("\n")))
    },
    Tool(
      "call_api",
      "任意の REST API を呼ぶ（画面でできることはすべてできる。list_api_endpoints を参照）。権限はこのキーの持ち主と同じ。",
      schema(
        List("method", "path"),
        "method" -> str("GET / POST / PUT / PATCH / DELETE"),
        "path" -> str("/api/ で始まるパス（クエリを含めてよい）"),
        "body" -> obj("JSON の本文")
      ),
      readOnly = false
    ) {
      (call, a) =>
        (need(a, "method").map(_.toUpperCase), need(a, "path")).tupled.withArgs {
          (m, p) =>
            (
              Method.fromString(m).toOption.filter(x => Set("GET", "POST", "PUT", "PATCH", "DELETE")(x.name)),
              Uri.fromString(p).toOption.filter(_.path.renderString.startsWith("/api/"))
            ) match {
              case (Some(method), Some(uri)) => call(method, uri, a.downField("body").focus.filterNot(_.isNull)).asText
              case (None, _) => IO.pure(Left("method は GET / POST / PUT / PATCH / DELETE です"))
              case _ => IO.pure(Left("path は /api/ で始めてください"))
            }
        }
    }
  )

  private val byName = tools.map(t => t.name -> t).toMap

  // ------------------------------------------------------------------ JSON-RPC

  private def ok(id: Json, result: Json): Json = Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id, "result" -> result)
  private def rpcFail(id: Json, code: Int, msg: String): Json =
    Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id, "error" -> Json.obj("code" -> code.asJson, "message" -> msg.asJson))
  private def toolResult(r: Either[String, String]): Json =
    Json.obj(
      "content" -> Json.arr(Json.obj("type" -> "text".asJson, "text" -> r.merge.asJson)),
      "isError" -> r.isLeft.asJson
    )

  private def handle(call: Caller, s: AuthSession, msg: Json): IO[Option[Json]] = {
    val c = msg.hcursor
    val params = c.downField("params")
    c.downField("id").focus match {
      case None => IO.pure(None)
      case Some(id) =>
        c.get[String]("method").getOrElse("") match {
          case "initialize" =>
            val asked = params.get[String]("protocolVersion").toOption
            IO.pure(Some(ok(
              id,
              Json.obj(
                "protocolVersion" -> asked.filter(Versions.contains).getOrElse(Versions.head).asJson,
                "capabilities" -> Json.obj("tools" -> Json.obj("listChanged" -> false.asJson)),
                "serverInfo" -> Json.obj("name" -> "stui".asJson, "version" -> "0.1.0".asJson),
                "instructions" ->
                  ("stui（チームのチャット）のチャンネル・DM・スレッド・メッセージを読み書きするツールです。" +
                    "list_channels で参加しているチャンネルを、read_channel でメッセージを、search_messages（絞り込み構文が使える）で過去の発言を探します。" +
                    "専用のツールがない操作は list_api_endpoints で REST API を調べ、call_api で行えます。権限はこの APIキーの持ち主と同じです。").asJson
              )
            )))
          case "ping" => IO.pure(Some(ok(id, Json.obj())))
          case "tools/list" => IO.pure(Some(ok(id, Json.obj("tools" -> tools.map(_.json).asJson))))
          case "tools/call" =>
            val name = params.get[String]("name").getOrElse("")
            byName.get(name) match {
              case None => IO.pure(Some(rpcFail(id, -32602, s"unknown tool: $name")))
              case Some(t) if !s.canWrite && !t.readOnly =>
                IO.pure(Some(ok(id, toolResult(Left("参照専用の APIキーでは変更できません")))))
              case Some(t) =>
                val args = params.downField("arguments").focus.filter(_.isObject).getOrElse(Json.obj()).hcursor
                t.run(call, args).handleError(e => Left(s"内部エラー: ${e.getMessage}")).map(r =>
                  Some(ok(id, toolResult(r)))
                )
            }
          case "resources/list" => IO.pure(Some(ok(id, Json.obj("resources" -> Json.arr()))))
          case "prompts/list" => IO.pure(Some(ok(id, Json.obj("prompts" -> Json.arr()))))
          case other => IO.pure(Some(rpcFail(id, -32601, s"method not found: $other")))
        }
    }
  }

  private val unauthorized =
    Response[IO](Status.Unauthorized).putHeaders(`WWW-Authenticate`(Challenge(
      "Bearer",
      "stui"
    ))).withEntity(ErrorDto("APIキー（Authorization: Bearer）が必要です"))

}
