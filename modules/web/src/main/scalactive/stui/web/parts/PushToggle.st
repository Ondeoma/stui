<script lang="scala">
import stui.web.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// プッシュ通知（PWA）のオン・オフ（この端末で受け取る）. 設定の画面に置く.
case class State(state: RV[String] = RV(""), busy: RV[Boolean] = RV(false), error: RV[String] = RV(""))

def initState() = State()

def mounted(s: State): Unit = Pwa.state().foreach(s.state := _)

def text(st: String): String = st match {
  case "on"      => "この端末でプッシュ通知を受け取っています（ブラウザを閉じていても届きます）"
  case "off"     => "この端末でプッシュ通知を受け取る（ブラウザを閉じていても届きます）"
  case "denied"  => "ブラウザの設定で通知が拒否されています。サイトの設定から通知を許可してください"
  case "install" => "iPhone・iPad では、共有ボタンの「ホーム画面に追加」で追加したアプリから受け取れます"
  case ""        => ""
  case _         => "このブラウザはプッシュ通知に対応していません"
}

def toggle(s: State): Unit = {
  s.busy := true; s.error := ""
  (if (s.state.v == "on") Pwa.disable() else Pwa.enable()).onComplete {
    case Success(st) => s.busy := false; s.state := st
    case Failure(e)  => s.busy := false; s.error := e.getMessage
  }
}

def label = (st: String) => if (st == "on") "受け取らない" else "受け取る"
def canToggle(s: State) = s.state.mapC(st => st == "on" || st == "off")
</script>

<template>
  <span class="push-toggle">
    <span class="muted small">{{ s.state.mapC(text) }} </span>
    <button v-if="canToggle(s)" type="button" class="small" :disabled="s.busy" @click="toggle(s)">{{ s.state.mapC(label) }}</button>
    <span v-if="s.error.mapC(_.nonEmpty)" class="error small"> {{ s.error }}</span>
  </span>
</template>
