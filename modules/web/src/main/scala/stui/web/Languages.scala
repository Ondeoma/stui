package stui.web

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** コードブロックで選べる言語（id → 表示名）. lowlight の common と scala・dockerfile（言語定義は frontend/src/langs.js）. */
object Languages {

  val names: List[(String, String)] = List(
    "plaintext" -> "プレーンテキスト",
    "bash" -> "Bash / シェル",
    "c" -> "C",
    "cpp" -> "C++",
    "csharp" -> "C#",
    "css" -> "CSS",
    "diff" -> "Diff",
    "go" -> "Go",
    "graphql" -> "GraphQL",
    "ini" -> "INI / TOML",
    "java" -> "Java",
    "javascript" -> "JavaScript",
    "json" -> "JSON",
    "kotlin" -> "Kotlin",
    "less" -> "Less",
    "lua" -> "Lua",
    "makefile" -> "Makefile",
    "markdown" -> "Markdown",
    "objectivec" -> "Objective-C",
    "perl" -> "Perl",
    "php" -> "PHP",
    "python" -> "Python",
    "r" -> "R",
    "ruby" -> "Ruby",
    "rust" -> "Rust",
    "scala" -> "Scala",
    "dockerfile" -> "Dockerfile",
    "scss" -> "SCSS",
    "shell" -> "シェルセッション",
    "sql" -> "SQL",
    "swift" -> "Swift",
    "typescript" -> "TypeScript",
    "vbnet" -> "VB.NET",
    "xml" -> "HTML / XML",
    "yaml" -> "YAML"
  )

  val labels: Map[String, String] = names.toMap

  /** エディタの言語の選択肢（[{id, label}]. 表示名の順）. */
  lazy val sorted: js.Array[js.Object] =
    names.map((id, label) => js.Dynamic.literal(id = id, label = label).asInstanceOf[js.Object]).toJSArray
      .sort((a, b) =>
        a.asInstanceOf[js.Dynamic].label.localeCompare(b.asInstanceOf[js.Dynamic].label).asInstanceOf[Int]
      )

}
