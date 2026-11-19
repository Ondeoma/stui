package stui.api

import cats.effect.*
import cats.syntax.all.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import stui.core.*
import stui.db.*
import stui.shared.*

/** 検索（メッセージ・チャンネル・人・ファイル）。 */
final class SearchRoutes(search: SearchStore,
                         channels: ChannelStore,
                         users: UserStore,
                         val sessions: SessionAuth,
                         val access: Access2)
  extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "search" => getSearch(req)
  }

  private def getSearch(req: Request[IO]): IO[Response[IO]] =
    authed(req) { s =>
      val q = param(req, "q").getOrElse("")
      val limit = intParam(req, "limit", 50, 200)
      val offset = param(req, "offset").flatMap(_.toIntOption).filter(_ >= 0).getOrElse(0)
      SearchQuery.parse(q) match {
        case Left(e) => Ok(SearchResultDto(Nil, 0, Nil, Nil, Nil, Some(e)))
        case Right(query) if query.isEmpty => Ok(SearchResultDto(Nil, 0, Nil, Nil, Nil, None))
        case Right(query) =>
          val words = query.text.mkString(" ")
          val extras =
            if offset > 0 || words.isEmpty then
              IO.pure((List.empty[ChannelDto], List.empty[UserBrief], List.empty[FileHitDto]))
            else
              (
                channels.browse(s.user.id, Some(words), archived = false, 8),
                users.list(Some(words), includeDisabled = false, 8).map(_.map(u =>
                  UserBrief(u.id, u.name, u.avatarUrl, u.bot)
                )),
                search.files(s.user.id, query.text, 10)
              ).tupled
          (search.search(s.user.id, Op.zone(s.user.timezone), query, limit, offset), extras).tupled.flatMap {
            case (Left(e), _) => Ok(SearchResultDto(Nil, 0, Nil, Nil, Nil, Some(e)))
            case (Right((hits, total)), (cs, us, fs)) => Ok(SearchResultDto(hits, total, cs, us, fs, None))
          }
      }
    }

}
