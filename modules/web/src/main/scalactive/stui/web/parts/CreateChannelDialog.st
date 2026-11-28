<script lang="scala">
import stui.web.*
import stui.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// チャンネルを作る（名前・公開/非公開・ボイスチャンネル・トピック・最初のメンバー）
case class State(name: RV[String] = RV(""), priv: RV[Boolean] = RV(false), topic: RV[String] = RV(""), purpose: RV[String] = RV(""),
                 members: RV[List[UserBrief]] = RV(Nil), voice: RV[Boolean] = RV(false), act: ActionState)

def initState() = State(voice = RV(Store.dialog.v == "voice"), act = useAction())

def close(): Unit = Store.dialog := ""

def create(s: State): Unit = if (!s.act.busy.v) {
  val r = CreateChannelReq(s.name.v, if (s.priv.v) ChannelKind.Private else ChannelKind.Public, Some(s.topic.v), Some(s.purpose.v), s.members.v.map(_.id), Some(s.voice.v))
  s.act.run(Api.createChannel(r)).foreach { c => close(); Store.replace(c); Store.openChannel(c.id) }
}

def add(s: State, u: UserBrief): Unit = if (!s.members.v.exists(_.id == u.id) && !Store.myId.contains(u.id)) s.members := s.members.v :+ u
</script>

<template>
  <Modal :onClose="_ => close()">
    <form @submit.prevent="create(s)">
      <h2>{{ s.voice.mapC(v => if (v) "ボイスチャンネルを作る" else "チャンネルを作る") }}</h2>
      <label class="field">名前<input type="text" v-model="s.name" placeholder="例: 開発・お知らせ" v-focus maxlength="80"></label>
      <label class="check"><input type="checkbox" v-model="s.priv"> 🔒 非公開（招待した人だけが見られる）</label>
      <label class="check"><input type="checkbox" v-model="s.voice"> 🔊 ボイスチャンネル（いつでも入れる通話の部屋. 画面共有もできる. チャットも使える）</label>
      <label class="field">トピック（任意）<input type="text" v-model="s.topic" placeholder="いま話していること" maxlength="250"></label>
      <label class="field">説明（任意）<input type="text" v-model="s.purpose" placeholder="何のためのチャンネルか" maxlength="250"></label>
      <div class="field">最初のメンバー（任意）
        <UserPicker placeholder="名前・メールで探す" :search="UserSearch.search" :delay="150" :onPick="p => UserSearch(p).foreach(add(s, _))" />
        <div class="chips">
          <span v-for="u in s.members" :key="u.id" class="chip"><Avatar :user="Some(person(u))" :size="18" /> {{ u.name }}
            <button type="button" class="x" @click="s.members := s.members.v.filterNot(_.id == u.id)">×</button></span>
        </div>
      </div>
      <p v-if="s.act.failed" class="error">{{ s.act.errorMessage }}</p>
      <div class="actions">
        <button type="button" @click="close()">キャンセル</button>
        <button type="submit" class="primary" :disabled="s.act.busy">作成</button>
      </div>
    </form>
  </Modal>
</template>

<style scoped>
h2 { margin: 0 0 1rem; font-size: 1.15rem; }
.chips { display: flex; flex-wrap: wrap; gap: .3rem; margin-top: .4rem; }
.chip { display: inline-flex; align-items: center; gap: .3rem; border: 1px solid var(--line); border-radius: 999px; padding: .1rem .5rem; font-size: .85rem; }
.x { border: none; background: none; padding: 0 .2rem; }
</style>
