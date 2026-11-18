package stui.storage

import cats.effect.*
import fs2.Stream
import fs2.io.file.Path

import java.security.MessageDigest


final case class BlobMeta(contentType: String, size: Long)

/** バイナリ(添付ファイル)の保存先。S3 互換ストレージ（開発は ScalaS3）。 */
trait BlobStore:
  def put(key: String, file: Path, contentType: String): IO[Unit]
  def get(key: String): IO[Option[(BlobMeta, Stream[IO, Byte])]]
  def delete(key: String): IO[Unit]
  /** 中身を別のキーに複製する（ページの複製用）。元がなければ false。 */
  def copy(from: String, to: String): IO[Boolean]

object BlobStore:
  /** ファイルの SHA-256（16進）。 */
  def sha256Hex(p: Path): IO[String] = IO.blocking {
    val md = MessageDigest.getInstance("SHA-256")
    val in = java.nio.file.Files.newInputStream(p.toNioPath)
    try
      val buf = new Array[Byte](64 * 1024)
      var n   = in.read(buf)
      while n > 0 do { md.update(buf, 0, n); n = in.read(buf) }
      md.digest().map(b => f"${b & 0xff}%02x").mkString
    finally in.close()
  }
