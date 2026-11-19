package stui.auth

/** stui の APIキー（stui_ で始まる）. */
object ApiKeys extends sbirds.auth.ApiKeys("stui_")

/** stui の秘密の値の暗号化（鍵の salt は stui 固有. 変えると保存済みの値を復号できなくなる）. */
object SecretBox {
  def apply(secret: String): sbirds.auth.SecretBox = new sbirds.auth.SecretBox(secret, "stui-secret-box:")
}

type SecretBox = sbirds.auth.SecretBox
