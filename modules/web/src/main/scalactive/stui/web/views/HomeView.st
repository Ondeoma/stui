<script lang="scala">
import stui.web.*
import stui.shared.*

import java.util.UUID

// 最初の画面: 前に開いていたチャンネル（なければ最初のチャンネル）へ移る
def mounted(): Unit = {
  val last = scala.util.Try(UUID.fromString(Prefs.get("last", ""))).toOption.filter(id => Store.channel(id).isDefined)
  val first = Store.channels.v.find(c => c.autoJoin && !ChannelKind.isDm(c.kind)).orElse(Store.channels.v.headOption).map(_.id)
  last.orElse(first) match {
    case Some(id) => Router.go(s"/c/$id")
    case None     => Router.go("/browse")
  }
}
</script>

<template>
  <div class="page muted">読み込み中…</div>
</template>
