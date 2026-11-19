<script lang="scala">
import stui.web.*
import org.scalajs.dom

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 開発用のログイン（DEV_LOGIN=true のとき。本番は OIDC の IdP の画面になる）
case class State(name: RV[String] = RV(""), email: RV[String] = RV(""), admin: RV[Boolean] = RV(true), error: RV[String] = RV(""))

def initState() = State()

def returnTo(): String = Option(new dom.URL(dom.window.location.href).searchParams.get("returnTo")).filter(_.startsWith("/")).getOrElse("/")

def login(s: State): Unit =
  Api.devLogin(s.name.v.trim, s.email.v.trim, s.admin.v, returnTo()).onComplete {
    case Success(_) => dom.window.location.href = returnTo()
    case Failure(e) => s.error := e.getMessage
  }

def mounted(): Unit = pageTitle("ログイン")
</script>

<template>
  <div class="page narrow">
    <div class="card login">
      <img src="/logo.svg" alt="stui" width="96" height="96" class="login-logo">
      <h1>{{ AppNames.self }} にログイン</h1>
      <p class="muted small">開発用のログインです（IdP が設定されていない環境だけで使えます）。</p>
      <form @submit.prevent="login(s)">
        <label class="field"><span>名前</span><input type="text" v-model="s.name" placeholder="山田 太郎" autofocus></label>
        <label class="field"><span>メールアドレス（任意）</span><input type="email" v-model="s.email"></label>
        <label class="row small"><input type="checkbox" v-model="s.admin"> 管理者としてログイン</label>
        <p v-if="s.error.mapC(_.nonEmpty)" class="error">{{ s.error }}</p>
        <p><button type="submit" class="primary">ログイン</button></p>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-logo { display: block; margin: 0 auto .6rem; }
.login { max-width: 420px; margin: 8vh auto 0; padding: 1.6rem; }
</style>
