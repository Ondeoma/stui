package stui.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import stui.shared.*

import java.util.UUID

final case class EmojiRow(
  name: String,
  s3Key: String,
  contentType: String,
  createdBy: Option[UUID])

/** リアクションに使えるカスタム絵文字（システム全体）。 */
final class EmojiStore(
  xa: Transactor[IO]) extends TxStore(xa) {

  def list: IO[List[CustomEmojiDto]] = tx(
    sql"select e.name, u.name from custom_emoji e left join app_user u on u.id = e.created_by order by e.name"
      .query[(String, Option[String])].map(
        (
          n,
          by
        ) => CustomEmojiDto(n, CustomEmoji.url(n), by)
      ).to[List]
  )

  def find(
    name: String
  ): IO[Option[EmojiRow]] = tx(
    sql"select name, s3_key, content_type, created_by from custom_emoji where name = $name".query[EmojiRow].option
  )

  /** 追加する（同じ名前があれば false）。 */
  def create(
    name: String,
    key: String,
    contentType: String,
    by: UUID
  ): IO[Boolean] = tx(
    sql"""insert into custom_emoji (id, name, s3_key, content_type, created_by) values (${UUID.randomUUID()}, $name, $key, $contentType, $by)
          on conflict (name) do nothing""".update.run.map(_ > 0)
  )

  def delete(
    name: String
  ): IO[Unit] = tx(sql"delete from custom_emoji where name = $name".update.run.void)
}
