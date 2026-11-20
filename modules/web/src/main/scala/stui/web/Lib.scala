package stui.web

import stui.shared.UserBrief

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// sbirds-lib の画面の部品（.st からは stui.web.* として見える）
export sbirds.web.{
  ApiError,
  AppNames,
  Backdrop,
  Dialog,
  DialogReq,
  Dom,
  Fallback,
  Formats,
  Images,
  Observe,
  Opt,
  Person,
  Pwa,
  Toasts
}

/** アイコン（parts/Avatar.st）に出す人. */
def person(u: UserBrief): Person = Person(u.id.toString, u.name, u.avatarUrl)

/** parts/UserPicker で探す人（ボットは出さない. 選んだ Person から UserBrief を引く）. */
object UserSearch extends sbirds.web.PeopleSearch[UserBrief](q => Api.users(q).map(_.filterNot(_.bot)), person)
