<script lang="scala">
import stui.web.*
import stui.shared.*

// 一覧（検索・保存済み・スレッド）のメッセージ1件. 押すとそのメッセージを開く（thread なら、そのメッセージのスレッドを開いた状態で）.
case class Props(m: MessageDto, channel: ChannelBrief, snippet: Option[String] = None, extra: String = "", thread: Boolean = false)

def go(props: Props): Unit =
  Router.go(Paths.message(props.channel.id, Some(props.m.id), props.m.rootId.orElse(Option.when(props.thread)(props.m.id))))

def name(props: Props): String =
  props.m.props.hcursor.get[String]("username").toOption.orElse(props.m.author.map(_.name)).getOrElse("（削除されたユーザー）")

def where(props: Props): String = Store.prefix(props.channel.kind) + Store.titleOf(props.channel) + (if (props.m.rootId.isDefined) "（スレッド）" else "")
def html(props: Props): String = props.snippet.filter(_.nonEmpty).getOrElse(props.m.html)
</script>

<template>
  <div class="ri" @click="go(props)">
    <div class="where muted small">{{ where(props) }} <span v-if="props.extra.nonEmpty">・{{ props.extra }}</span></div>
    <div class="row top">
      <Avatar :user="props.m.author" :size="32" />
      <div class="grow body">
        <div><b>{{ name(props) }}</b> <span class="muted small">{{ Fmt.dateTime(props.m.createdAt) }}</span></div>
        <div class="rich" v-html="html(props)"></div>
        <div v-if="props.m.attachments.nonEmpty" class="muted small">📎 {{ props.m.attachments.map(_.fileName).mkString("、") }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ri { border: 1px solid var(--line); border-radius: 10px; padding: .6rem .8rem; margin-bottom: .6rem; cursor: pointer; background: var(--card); }
.ri:hover { border-color: var(--accent); }
.top { align-items: flex-start; gap: .6rem; }
.body { min-width: 0; overflow-wrap: anywhere; }
.where { margin-bottom: .3rem; }
</style>
