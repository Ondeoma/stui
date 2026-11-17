package stui.storage

import cats.effect.*
import fs2.Stream
import fs2.io.file.Path


final case class BlobMeta(contentType: String, size: Long)

/** バイナリ(添付ファイル)の保存先。S3 互換ストレージ（開発は ScalaS3）。 */
trait BlobStore:
  def put(key: String, file: Path, contentType: String): IO[Unit]
  def get(key: String): IO[Option[(BlobMeta, Stream[IO, Byte])]]
  def delete(key: String): IO[Unit]
  /** 中身を別のキーに複製する（ページの複製用）。元がなければ false。 */
  def copy(from: String, to: String): IO[Boolean]
