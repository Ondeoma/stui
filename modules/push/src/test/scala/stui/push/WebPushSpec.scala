package stui.push

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.{Cipher, KeyAgreement, Mac}
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

/** ブラウザ（受け取る側）と同じ手順で復号して、暗号化と VAPID の署名を確かめる. */
class WebPushSpec extends munit.FunSuite:

  private def hmac(k: Array[Byte], d: Array[Byte]) = { val m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(k, "HmacSHA256")); m.doFinal(d) }
  private def hkdf(salt: Array[Byte], ikm: Array[Byte], info: Array[Byte], n: Int) = hmac(hmac(salt, ikm), info ++ Array[Byte](1)).take(n)
  private def u32(b: BigInteger) = { val a = b.toByteArray; if a.length >= 32 then a.takeRight(32) else Array.fill[Byte](32 - a.length)(0) ++ a }

  test("aes128gcm の本文を受け取る側の鍵で復号できる") {
    val g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp256r1"))
    val ua = g.generateKeyPair()
    val uaPub = ua.getPublic.asInstanceOf[ECPublicKey]
    val uaRaw = Array[Byte](4) ++ u32(uaPub.getW.getAffineX) ++ u32(uaPub.getW.getAffineY)
    val auth = new Array[Byte](16); new SecureRandom().nextBytes(auth)
    val msg = """{"title":"テスト","body":"こんにちは"}"""
    val body = WebPush.encrypt(Subscription("https://fcm.googleapis.com/fcm/send/x", WebPush.enc(uaRaw), WebPush.enc(auth)), msg.getBytes(UTF_8))

    val bb = ByteBuffer.wrap(body)
    val salt = new Array[Byte](16); bb.get(salt)
    assertEquals(bb.getInt, 4096)
    val idlen = bb.get().toInt
    val asRaw = new Array[Byte](idlen); bb.get(asRaw)
    val cipher = new Array[Byte](bb.remaining()); bb.get(cipher)

    val asPub = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(
      new ECPoint(new BigInteger(1, asRaw.slice(1, 33)), new BigInteger(1, asRaw.slice(33, 65))), uaPub.getParams))
    val ka = KeyAgreement.getInstance("ECDH"); ka.init(ua.getPrivate); ka.doPhase(asPub, true)
    val ikm = hkdf(auth, ka.generateSecret(), "WebPush: info".getBytes(UTF_8) ++ Array[Byte](0) ++ uaRaw ++ asRaw, 32)
    val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".getBytes(UTF_8) ++ Array[Byte](0), 16)
    val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".getBytes(UTF_8) ++ Array[Byte](0), 12)
    val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce))
    val plain = c.doFinal(cipher)
    assertEquals(plain.last, 2.toByte)
    assertEquals(new String(plain.dropRight(1), UTF_8), msg)
  }

  test("VAPID の JWT を公開鍵で検証できる") {
    val keys = WebPush.generateKeys()
    val h = WebPush.vapidAuthorization(keys, "https://fcm.googleapis.com/fcm/send/abc", "mailto:ops@example.com", 1_800_000_000L)
    val jwt = h.stripPrefix("vapid t=").takeWhile(_ != ',')
    assert(h.endsWith(s"k=${keys.publicKey}"))
    val parts = jwt.split('.')
    assert(new String(WebPush.dec(parts(1)), UTF_8).contains("\"aud\":\"https://fcm.googleapis.com\""))
    val raw = WebPush.dec(keys.publicKey)
    val params = { val g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp256r1")); g.generateKeyPair().getPublic.asInstanceOf[ECPublicKey].getParams }
    val pub = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(new BigInteger(1, raw.slice(1, 33)), new BigInteger(1, raw.slice(33, 65))), params))
    val v = Signature.getInstance("SHA256withECDSAinP1363Format"); v.initVerify(pub); v.update(s"${parts(0)}.${parts(1)}".getBytes(UTF_8))
    assert(v.verify(WebPush.dec(parts(2))))
  }

  test("送り先はプッシュのサービス（https）だけ") {
    assert(WebPush.allowedEndpoint("https://fcm.googleapis.com/fcm/send/x"))
    assert(WebPush.allowedEndpoint("https://web.push.apple.com/abc"))
    assert(WebPush.allowedEndpoint("https://wns2-par02p.notify.windows.com/w/?token=x"))
    assert(!WebPush.allowedEndpoint("http://fcm.googleapis.com/x"))
    assert(!WebPush.allowedEndpoint("https://169.254.169.254/latest"))
    assert(!WebPush.allowedEndpoint("https://fcm.googleapis.com.evil.example/x"))
  }
