package stui.db

import doobie.*
import doobie.implicits.*

/** SQL の部品（doobie の Fragments を List で使うため）。 */
object Sql {

  /** set a = 1, b = 2（空のリストは渡さないこと）。 */
  def set(
    fs: List[Fragment]
  ): Fragment = fr"set" ++ fs.reduce(_ ++ fr"," ++ _)

  /** where a and b（空なら何も付けない）。 */
  def whereAnd(
    fs: List[Fragment]
  ): Fragment =
    if fs.isEmpty then Fragment.empty else fr"where" ++ fs.map(f => fr"(" ++ f ++ fr")").reduce(_ ++ fr"and" ++ _)
  def and(
    fs: List[Fragment]
  ): Fragment =
    if fs.isEmpty then fr"true" else fs.map(f => fr"(" ++ f ++ fr")").reduce(_ ++ fr"and" ++ _)
  def or(
    fs: List[Fragment]
  ): Fragment =
    if fs.isEmpty then fr"false" else fs.map(f => fr"(" ++ f ++ fr")").reduce(_ ++ fr"or" ++ _)
  def commas(
    fs: List[Fragment]
  ): Fragment = fs.reduce(_ ++ fr"," ++ _)
}
