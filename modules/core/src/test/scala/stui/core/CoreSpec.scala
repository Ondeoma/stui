package stui.core

import io.circe.Json
import io.circe.parser.parse
import stui.shared.HookIcons

import java.time.{LocalDate, LocalTime, ZoneId}
import java.util.UUID

class CoreSpec extends munit.FunSuite:

  test("SearchQuery: 絞り込み構文") {
    val q = SearchQuery.parse("""in:#開発 from:@山田 has:link -is:thread "障害 報告" -テスト after:-7d""").toOption.get
    assertEquals(q.values("in"), List("開発"))
    assertEquals(q.values("from"), List("山田"))
    assertEquals(q.values("has"), List("link"))
    assertEquals(q.negValues("is"), List("thread"))
    assertEquals(q.text, List("障害 報告"))
    assertEquals(q.negText, List("テスト"))
    assert(SearchQuery.parse("has:nothing").isLeft)
    assert(SearchQuery.parse("from:").isLeft)
    // 知らないキーは普通の語
    assertEquals(SearchQuery.parse("http://example.com").toOption.get.text, List("http://example.com"))
  }

  test("SearchQuery: 日付と期間") {
    val today = LocalDate.of(2026, 11, 16)
    assertEquals(SearchQuery.date("-7d", today), Some(LocalDate.of(2026, 11, 9)))
    assertEquals(SearchQuery.date("yesterday", today), Some(LocalDate.of(2026, 11, 15)))
    assertEquals(SearchQuery.period("2026-11", today), Some(LocalDate.of(2026, 11, 1) -> LocalDate.of(2026, 12, 1)))
    assertEquals(SearchQuery.period("2026-11-03", today), Some(LocalDate.of(2026, 11, 3) -> LocalDate.of(2026, 11, 4)))
    assertEquals(SearchQuery.phrase("a\"b"), "\"a\\\"b\"")
  }

  test("RichText: 許可した要素だけを HTML にする") {
    val id = UUID.randomUUID()
    val doc = parse(s"""{"type":"doc","content":[
      {"type":"paragraph","content":[{"type":"text","text":"<b>x</b>","marks":[{"type":"bold"}]},
        {"type":"text","text":"link","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]},
        {"type":"mention","attrs":{"id":"$id","label":"山田"}}]},
      {"type":"script","content":[{"type":"text","text":"y"}]},
      {"type":"image","attrs":{"src":"javascript:x"}}
    ]}""").toOption.get
    val html = RichText.toHtml(doc)
    assertEquals(html, s"""<p><strong>&lt;b&gt;x&lt;/b&gt;</strong>link<span class="mention" data-user-id="$id">@山田</span></p>y""")
    assertEquals(RichText.mentions(doc), Set(id))
    assertEquals(RichText.toPlain(doc), "<b>x</b>link@山田\ny")
  }

  test("RichText: 画像の幅・寄せ方向と、セルの色（許可した値だけ）") {
    val doc = parse("""{"type":"doc","content":[
      {"type":"image","attrs":{"src":"/api/attachments/x","alt":"a","width":320.4,"align":"center"}},
      {"type":"image","attrs":{"src":"/api/attachments/y","width":"100%","align":"evil\" onload=\"x"}},
      {"type":"table","content":[{"type":"tableRow","content":[
        {"type":"tableCell","attrs":{"backgroundColor":"yellow"},"content":[{"type":"paragraph"}]},
        {"type":"tableCell","attrs":{"backgroundColor":"url(x)"},"content":[{"type":"paragraph"}]}]}]}
    ]}""").toOption.get
    assertEquals(RichText.toHtml(doc),
      """<img src="/api/attachments/x" alt="a" width="320" data-align="center" loading="lazy"><img src="/api/attachments/y" alt="" loading="lazy">""" +
        """<table><tbody><tr><td data-bg="yellow"><p></p></td><td><p></p></td></tr></tbody></table>""")
  }

  test("RichText: グループ・役割へのメンション") {
    val g = UUID.randomUUID()
    val doc = parse(s"""{"type":"doc","content":[{"type":"paragraph","content":[
      {"type":"mention","attrs":{"id":"g:$g","label":"開発"}},{"type":"mention","attrs":{"id":"r:channel","label":"channel"}},
      {"type":"mention","attrs":{"id":"r:evil","label":"x"}}]}]}""").toOption.get
    assertEquals(RichText.groupMentions(doc), Set(s"g:$g", "r:channel"))
    assertEquals(RichText.mentions(doc), Set.empty[UUID])
    assert(RichText.toHtml(doc).contains(s"""<span class="mention group" data-group="g:$g">@開発</span>"""))
  }


  test("Recurrence: 毎日・平日・毎週・毎月（月末に丸める）・1回だけ") {
    val z = ZoneId.of("Asia/Tokyo")
    val t = LocalTime.of(9, 0)
    val after = LocalDate.of(2026, 11, 14).atTime(10, 0).atZone(z).toInstant // 土曜 10:00
    def at(y: Int, m: Int, d: Int) = Some(LocalDate.of(y, m, d).atTime(t).atZone(z).toInstant)
    assertEquals(Recurrence.next("daily", None, t, None, None, z, after), at(2026, 11, 15))
    assertEquals(Recurrence.next("weekdays", None, t, None, None, z, after), at(2026, 11, 16))
    assertEquals(Recurrence.next("weekly", None, t, Some(3), None, z, after), at(2026, 11, 18))
    assertEquals(Recurrence.next("monthly", None, t, None, Some(31), z, after), at(2026, 11, 30))
    assertEquals(Recurrence.next("monthly", None, t, None, Some(14), z, after), at(2026, 12, 14))
    assertEquals(Recurrence.next("once", Some(LocalDate.of(2026, 11, 20)), t, None, None, z, after), at(2026, 11, 20))
    assertEquals(Recurrence.next("once", Some(LocalDate.of(2026, 11, 1)), t, None, None, z, after), None)
    assertEquals(Recurrence.parseTime("7:05"), Some(LocalTime.of(7, 5)))
    assertEquals(Recurrence.parseTime("25:00"), None)
  }

  test("RichText: Markdown 風の入力と Slack の書き方のリンク") {
    val doc = RichText.fromMarkdown("# 見出し\n\n- a\n- **b**\n\n本文 [リンク](https://example.com) <https://x.example/a|エックス> ~~消~~")
    assertEquals(RichText.toHtml(doc),
      """<h1>見出し</h1><ul><li><p>a</p></li><li><p><strong>b</strong></p></li></ul><p>本文 <a href="https://example.com" target="_blank" rel="noopener noreferrer nofollow">リンク</a> """ +
        """<a href="https://x.example/a" target="_blank" rel="noopener noreferrer nofollow">エックス</a> <s>消</s></p>""")
    assert(RichText.isBlank(RichText.fromPlain("  ")))
  }

  test("ChannelName・DmKey") {
    assertEquals(ChannelName.normalize(" #開発 "), Right("開発"))
    assert(ChannelName.normalize("a\nb").isLeft)
    val a = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val b = UUID.fromString("00000000-0000-0000-0000-000000000001")
    assertEquals(DmKey.of(List(a, b, a)), s"$b,$a")
  }

  test("RichText: リンクの付いていない URL もリンクにする") {
    val doc = RichText.fromPlain("見て https://example.com/a?b=1&c=2。 `x`")
    assertEquals(RichText.toHtml(doc),
      """<p>見て <a href="https://example.com/a?b=1&amp;c=2" target="_blank" rel="noopener noreferrer nofollow">https://example.com/a?b=1&amp;c=2</a>。 `x`</p>""")
  }

  test("RichText: Markdown のバックスラッシュエスケープ") {
    def html(md: String) = RichText.toHtml(RichText.fromMarkdown(md))
    assertEquals(html("\\#general a\\*b\\* \\_x\\_ \\[l\\](u) \\<y\\> \\!\\|\\~\\~z\\~\\~ \\\\"), "<p>#general a*b* _x_ [l](u) &lt;y&gt; !|~~z~~ \\</p>")
    // 行頭の記号も見出し・箇条書き・引用にしない
    assertEquals(html("\\# 題\n\n\\- a\n\n\\> b\n\n1\\. c"), "<p># 題</p><p>- a</p><p>&gt; b</p><p>1. c</p>")
    // 記号でない文字の前の \ とコードの中の \ は残す
    assertEquals(html("C:\\path\\日本 `a\\*b` \\`x`"), "<p>C:\\path\\日本 <code>a\\*b</code> `x`</p>")
    // 記法の中のエスケープ
    assertEquals(html("**a\\*b** *c\\*d* [x\\]y](https://e.example/\\(1\\))"),
      """<p><strong>a*b</strong> <em>c*d</em> <a href="https://e.example/(1)" target="_blank" rel="noopener noreferrer nofollow">x]y</a></p>""")
    assertEquals(RichText.toPlain(RichText.fromMarkdown("- \\[ \\] 名前\\_1")), "[ ] 名前_1")
  }

  test("HookIcons: icon_emoji の :name: を絵文字にし、描けないものは描かない") {
    assertEquals(HookIcons.normalize(":large_green_circle:"), "🟢")
    assertEquals(HookIcons.normalize(":red_circle:"), "🔴")
    assertEquals(HookIcons.normalize(" 🚨 "), "🚨")
    assertEquals(HookIcons.normalize(":unknown_name:"), ":unknown_name:")
    assert(HookIcons.drawable("🟢"))
    assert(HookIcons.drawable("⚠️"))
    assert(!HookIcons.drawable(":unknown_name:"))
    assert(!HookIcons.drawable("green_circle:"))
    assert(!HookIcons.drawable(""))
  }

  test("RichText: 太字の中のリンク") {
    assertEquals(RichText.toHtml(RichText.fromMarkdown("**[題](https://x.example)** 本文")),
      """<p><strong><a href="https://x.example" target="_blank" rel="noopener noreferrer nofollow">題</a></strong> 本文</p>""")
  }

  test("RichText: 深すぎる入れ子・危ない URL を受け付けない") {
    val deep = (1 to 100).foldLeft(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString("x")))((n, _) =>
      Json.obj("type" -> Json.fromString("blockquote"), "content" -> Json.arr(n)))
    assert(!RichText.valid(Json.obj("type" -> Json.fromString("doc"), "content" -> Json.arr(deep))))
    assert(RichText.valid(RichText.fromMarkdown("# ok\n\n- a")))
    assertEquals(RichText.safeUrl("/\\evil.com"), None)
    assertEquals(RichText.safeUrl("//evil.com"), None)
    assertEquals(RichText.safeUrl("javascript:alert(1)"), None)
    assertEquals(RichText.safeUrl("/c/1"), Some("/c/1"))
  }

  test("RichText: 閉じない記号が続く長い Markdown でも時間がかからない・外部の画像は出さない") {
    val t0 = System.nanoTime()
    List("[", "<http://", "*a", "**", "`a``", "\\*").foreach(p => RichText.fromMarkdown(p * (RichText.MaxMarkdown / p.length)))
    assert((System.nanoTime() - t0) / 1000000 < 3000, "fromMarkdown が遅すぎる")
    val img = (src: String) => RichText.toHtml(parse(s"""{"type":"doc","content":[{"type":"image","attrs":{"src":"$src"}}]}""").toOption.get)
    assert(!img("https://tracker.example/t.png").contains("<img"))
    assert(img("/api/files/1/a.png").contains("""<img src="/api/files/1/a.png""""))
  }
