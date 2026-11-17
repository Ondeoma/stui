// コードのハイライトの言語定義（大きいので、必要になったときに別のファイルとして読み込む）
import { common } from 'lowlight'
import scala from 'highlight.js/lib/languages/scala'
import dockerfile from 'highlight.js/lib/languages/dockerfile'

export const grammars = { ...common, scala, dockerfile }
