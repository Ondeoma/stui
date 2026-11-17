// エディタと表示で共有するハイライト（lowlight）。言語定義は loadLanguages() で後から読み込む。
// 表示（.rich）のハイライトの DOM の処理は Scala.js（Highlight.scala）が行い、window.stuiLowlight を使う。
import { createLowlight } from 'lowlight'

export const lowlight = createLowlight()

let loading = null
const listeners = new Set()

/** 言語定義を読み込んだときに呼ぶ（エディタの表示の更新）。戻り値で登録をやめる。 */
export function onLanguages(f) {
  listeners.add(f)
  return () => listeners.delete(f)
}

export const languagesReady = () => lowlight.listLanguages().length > 0

export function loadLanguages() {
  if (!loading) {
    loading = import('./langs.js').then((m) => {
      lowlight.register(m.grammars)
      listeners.forEach((f) => f())
    })
  }
  return loading
}

/** text をハイライトした結果（hast）。lang が無いか登録されていなければ自動判定。 */
export const highlight = (lang, text) =>
  (lang && lowlight.registered(lang) ? lowlight.highlight(lang, text) : lowlight.highlightAuto(text))
