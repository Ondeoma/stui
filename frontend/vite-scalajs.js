import { spawn } from 'node:child_process'
import path from 'node:path'

/**
 * Scala.js(sbt の web プロジェクト)の出力を `import 'scalajs:main.js'` で読み込む Vite プラグイン（skiwi と同じ）。
 * 起動時・ビルド時に sbt で fastLinkJS / fullLinkJS を実行し、その出力先を解決する。
 */
export default function scalaJS(opts) {
  const prefix = 'scalajs:'
  let isDev = false
  let outDir
  return {
    name: 'stui:scalajs',
    config: () => ({ server: { fs: { allow: [path.resolve(opts.cwd)] } } }),
    configResolved(c) { isDev = c.command === 'serve' },
    async buildStart() {
      const task = `${opts.projectID}/${isDev ? 'fastLinkJSOutput' : 'fullLinkJSOutput'}`
      outDir = await printSbtTask(task, opts.cwd)
    },
    resolveId(source) {
      if (!source.startsWith(prefix)) return null
      if (!outDir) throw new Error('Scala.js の出力先が決まっていません（sbt の実行に失敗しました）')
      return `${outDir}/${source.substring(prefix.length)}`
    },
  }
}

function printSbtTask(task, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn('sbt', ['--batch', '-no-colors', '-Dsbt.supershell=false', `print ${task}`], { cwd, stdio: ['ignore', 'pipe', 'inherit'] })
    let out = ''
    child.stdout.setEncoding('utf-8')
    child.stdout.on('data', (d) => { out += d; process.stdout.write(d) })
    child.on('error', (e) => reject(new Error(`sbt を起動できません: ${e}`)))
    child.on('close', (code) => {
      if (code === 0) resolve(out.trimEnd().split('\n').at(-1).trim())
      else reject(new Error(`sbt ${task} が失敗しました (exit ${code})`))
    })
  })
}
