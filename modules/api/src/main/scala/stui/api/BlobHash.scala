package stui.api

import cats.effect.IO
import fs2.io.file.Path

import java.security.MessageDigest

/** 添付ファイルの重複の判定に使うハッシュ（sbirds-lib の BlobStore にはないため、stui に残す）. */
object BlobHash {

  /** ファイルの SHA-256（16進）。 */
  def sha256Hex(p: Path): IO[String] = IO.blocking {
    val md = MessageDigest.getInstance("SHA-256")
    val in = java.nio.file.Files.newInputStream(p.toNioPath)
    try {
      val buf = new Array[Byte](64 * 1024)
      var n = in.read(buf)
      while n > 0 do { md.update(buf, 0, n); n = in.read(buf) }
      md.digest().map(b => f"${b & 0xff}%02x").mkString
    } finally in.close()
  }
}
