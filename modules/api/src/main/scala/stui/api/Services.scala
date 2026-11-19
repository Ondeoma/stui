package stui.api

import stui.core.*

/** 投稿の作者（人か、ボット）。via は web / api / mcp / webhook / app / reminder / schedule。 */
final case class Author(user: User, via: String)
