<script lang="scala">
import stui.web.*
import stui.shared.*

// DM を始める（1人なら1対1、2人以上ならグループ DM. 誰も選ばなければ自分だけのメモ）
case class State(users: RV[List[UserBrief]] = RV(Nil))

def initState() = State()

def close(): Unit = Store.dialog := ""

def add(s: State, u: UserBrief): Unit =
  if (!s.users.v.exists(_.id == u.id) && !Store.myId.contains(u.id) && s.users.v.size < 8) s.users := s.users.v :+ u

def start(s: State): Unit = { close(); Store.openDm(s.users.v.map(_.id)) }

def label(s: State) = s.users.mapC(us => if (us.isEmpty) "自分だけのメモを開く" else if (us.size == 1) "DM を開く" else s"${us.size + 1}人のグループ DM を開く")
</script>

<template>
  <Modal :onClose="_ => close()">
    <form @submit.prevent="start(s)">
      <h2>ダイレクトメッセージ</h2>
      <UserPicker placeholder="名前・メールで探す" :search="UserSearch.search" :delay="150" :onPick="p => UserSearch(p).foreach(add(s, _))" :autofocus="true" />
      <div class="chips">
        <span v-for="u in s.users" :key="u.id" class="chip"><Avatar :user="Some(person(u))" :size="18" /> {{ u.name }}
          <button type="button" class="x" @click="s.users := s.users.v.filterNot(_.id == u.id)">×</button></span>
      </div>
      <p class="muted small">相手を選んでください（8人まで）。</p>
      <div class="actions">
        <button type="button" @click="close()">キャンセル</button>
        <button type="submit" class="primary">{{ label(s) }}</button>
      </div>
    </form>
  </Modal>
</template>

<style scoped>
h2 { margin: 0 0 1rem; font-size: 1.15rem; }
.chips { display: flex; flex-wrap: wrap; gap: .3rem; margin-top: .6rem; }
.chip { display: inline-flex; align-items: center; gap: .3rem; border: 1px solid var(--line); border-radius: 999px; padding: .1rem .5rem; font-size: .85rem; }
.x { border: none; background: none; padding: 0 .2rem; }
</style>
