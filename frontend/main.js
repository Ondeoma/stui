import './style.css'
import './src/editor.css'
import { createEditor } from './src/editor.js'
import { highlight, loadLanguages, languagesReady } from './src/lowlight.js'

// Scala.js の画面から使うライブラリ（エディタの Tiptap、コードのハイライトの lowlight）
window.stuiEditor = { create: createEditor }
window.stuiLowlight = { highlight, load: loadLanguages, ready: languagesReady }

import 'scalajs:main.js'
