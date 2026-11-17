<script lang="scala">
import stui.web.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// メッセージへのリンク（/m/{id}）: そのメッセージのチャンネル（スレッド）を開く
case class Props(messageId: UUID)

case class State(error: RV[String] = RV(""))

def initState() = State()

def mounted(s: State, props: Props): Unit =
  Api.message(props.messageId).onComplete {
    case scala.util.Success(m) =>
      Router.go(stui.shared.Paths.message(m.channelId, Some(m.id), m.rootId))
    case scala.util.Failure(e) => s.error := e.getMessage
  }
</script>

<template>
  <div class="page">
    <p v-if="s.error.mapC(_.nonEmpty)" class="error">{{ s.error }}</p>
    <p v-else class="muted">開いています…</p>
  </div>
</template>
