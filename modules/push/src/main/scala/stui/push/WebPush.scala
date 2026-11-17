package stui.push

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.*
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.security.spec.*
import java.util.Base64
import javax.crypto.{Cipher, KeyAgreement, Mac}
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

/** VAPID の鍵（P-256）. publicKey は非圧縮の点（65バイト）、privateKey は d（32バイト）. どちらも base64url（パディングなし）. */
final case class VapidKeys(publicKey: String, privateKey: String)

/** ブラウザが購読したときに渡す値（PushSubscription の endpoint・keys.p256dh・keys.auth）. */
final case class Subscription(endpoint: String, p256dh: String, auth: String)

/**
 * Web Push の暗号化（RFC 8291・aes128gcm（RFC 8188））と VAPID（RFC 8292）. JDK の暗号の機能だけで実装する.
 * 送る本文は暗号化され、プッシュのサービス（FCM・Mozilla・Apple など）には中身が見えない.
 */
object WebPush:

  private val b64  = Base64.getUrlEncoder.withoutPadding
  private val ub64 = Base64.getUrlDecoder

  def enc(b: Array[Byte]): String  = b64.encodeToString(b)
  def dec(s: String): Array[Byte]  = ub64.decode(s.trim.replace('+', '-').replace('/', '_').replace("=", ""))

  private lazy val params: ECParameterSpec =
    val g = KeyPairGenerator.getInstance("EC")
    g.initialize(new ECGenParameterSpec("secp256r1"))
    g.generateKeyPair().getPublic.asInstanceOf[ECPublicKey].getParams

  private def unsigned32(n: BigInteger): Array[Byte] =
    val b = n.toByteArray
    if b.length == 32 then b
    else if b.length > 32 then b.takeRight(32)
    else Array.fill[Byte](32 - b.length)(0) ++ b

  private def point(pub: ECPublicKey): Array[Byte] =
    Array[Byte](4) ++ unsigned32(pub.getW.getAffineX) ++ unsigned32(pub.getW.getAffineY)

  private def publicKey(raw: Array[Byte]): ECPublicKey =
    require(raw.length == 65 && raw(0) == 4, "公開鍵は非圧縮の P-256 の点（65バイト）にしてください")
    val x = new BigInteger(1, raw.slice(1, 33))
    val y = new BigInteger(1, raw.slice(33, 65))
    KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), params)).asInstanceOf[ECPublicKey]

  private def privateKey(d: Array[Byte]): ECPrivateKey =
    KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), params)).asInstanceOf[ECPrivateKey]

  /** VAPID の鍵を作る（初めて起動したときに1度だけ. DB に保存して全ノードで同じものを使う）. */
  def generateKeys(): VapidKeys =
    val g = KeyPairGenerator.getInstance("EC")
    g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom())
    val kp = g.generateKeyPair()
    VapidKeys(enc(point(kp.getPublic.asInstanceOf[ECPublicKey])), enc(unsigned32(kp.getPrivate.asInstanceOf[ECPrivateKey].getS)))

  private def hmac(key: Array[Byte], data: Array[Byte]): Array[Byte] =
    val m = Mac.getInstance("HmacSHA256")
    m.init(new SecretKeySpec(key, "HmacSHA256"))
    m.doFinal(data)

  /** HKDF（RFC 5869）. 出力は32バイトまで. */
  private def hkdf(salt: Array[Byte], ikm: Array[Byte], info: Array[Byte], len: Int): Array[Byte] =
    hmac(hmac(salt, ikm), info ++ Array[Byte](1)).take(len)

  /** 本文を暗号化する（aes128gcm. 1つのレコード）. 戻り値はそのまま POST の本文にする. */
  def encrypt(sub: Subscription, payload: Array[Byte]): Array[Byte] =
    require(payload.length <= 3800, "本文が大きすぎます（約 4KB まで）")
    val uaPublic = dec(sub.p256dh)
    val authSecret = dec(sub.auth)
    val g = KeyPairGenerator.getInstance("EC")
    g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom())
    val eph = g.generateKeyPair()
    val asPublic = point(eph.getPublic.asInstanceOf[ECPublicKey])
    val ka = KeyAgreement.getInstance("ECDH")
    ka.init(eph.getPrivate)
    ka.doPhase(publicKey(uaPublic), true)
    val ecdhSecret = ka.generateSecret()
    val keyInfo = "WebPush: info".getBytes(UTF_8) ++ Array[Byte](0) ++ uaPublic ++ asPublic
    val ikm   = hkdf(authSecret, ecdhSecret, keyInfo, 32)
    val salt  = new Array[Byte](16)
    new SecureRandom().nextBytes(salt)
    val cek   = hkdf(salt, ikm, "Content-Encoding: aes128gcm".getBytes(UTF_8) ++ Array[Byte](0), 16)
    val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".getBytes(UTF_8) ++ Array[Byte](0), 12)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce))
    val cipher = c.doFinal(payload ++ Array[Byte](2)) // 2 = 最後のレコード
    val header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length)
    header.put(salt).putInt(4096).put(asPublic.length.toByte).put(asPublic)
    header.array() ++ cipher

  /** VAPID の Authorization ヘッダーの値（`vapid t=<JWT>, k=<公開鍵>`）. aud はプッシュのサービスのオリジン. */
  def vapidAuthorization(keys: VapidKeys, endpoint: String, subject: String, nowSeconds: Long): String =
    val u = java.net.URI.create(endpoint)
    val aud = s"${u.getScheme}://${u.getHost}${if u.getPort > 0 then s":${u.getPort}" else ""}"
    val header = enc("""{"typ":"JWT","alg":"ES256"}""".getBytes(UTF_8))
    val claims = enc(s"""{"aud":"$aud","exp":${nowSeconds + 12 * 3600},"sub":"$subject"}""".getBytes(UTF_8))
    val signer = Signature.getInstance("SHA256withECDSAinP1363Format")
    signer.initSign(privateKey(dec(keys.privateKey)))
    signer.update(s"$header.$claims".getBytes(UTF_8))
    s"vapid t=$header.$claims.${enc(signer.sign())}, k=${keys.publicKey}"

  /**
   * 送り先として許すプッシュのサービス（https のみ）. 購読の endpoint はブラウザから届く値なので、
   * 内部のネットワークなどへ送らせない（SSRF を防ぐ）.
   */
  val allowedHosts: List[String] = List(
    "fcm.googleapis.com", "android.googleapis.com", "updates.push.services.mozilla.com", "push.services.mozilla.com",
    "web.push.apple.com", "notify.windows.com", "push.apple.com"
  )

  def allowedEndpoint(endpoint: String): Boolean =
    scala.util.Try(java.net.URI.create(endpoint)).toOption.exists { u =>
      u.getScheme == "https" && Option(u.getHost).exists(h => allowedHosts.exists(a => h == a || h.endsWith("." + a)))
    }
