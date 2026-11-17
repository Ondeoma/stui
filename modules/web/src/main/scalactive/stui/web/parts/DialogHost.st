<script lang="scala">
import stui.web.*

// 画面内のダイアログ（確認・入力・選択・お知らせ）. Dialog.confirm などで開く.
def okClass(d: DialogReq): String = if (d.danger) "danger" else "primary"
def list = Dialog.current.mapC(_.toList)
</script>

<template>
  <Modal v-for="d in list" :onClose="_ => Dialog.close(false)">
    <form class="dlg" @submit.prevent="Dialog.close(true)">
      <p class="msg">{{ d.message }}</p>
      <template v-for="v in d.input.toList">
        <Combo v-if="d.choices.nonEmpty" :bind="Some(v)" :options="Opt.of(d.choices)" />
        <input v-else type="text" v-model="v" class="full" autofocus>
      </template>
      <div class="actions">
        <button v-if="d.cancel" type="button" @click="Dialog.close(false)">キャンセル</button>
        <button type="submit" :class="okClass(d)" autofocus>{{ d.okLabel }}</button>
      </div>
    </form>
  </Modal>
</template>

<style scoped>
.msg { white-space: pre-wrap; margin: 0 0 .8rem; line-height: 1.6; }
.full { width: 100%; }
.dlg .actions button.danger { background: var(--danger); border-color: var(--danger); color: #fff; }
</style>
