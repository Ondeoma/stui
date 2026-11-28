package stui.web

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom
import stui.shared.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.Try

/**
 * ボイスチャンネル（1度に1つ）. 音声・画面共有は参加者どうしが WebRTC で直接送り合う（メッシュ. 相手ごとに RTCPeerConnection）.
 * サーバーは顔ぶれ・状態（/api/voice）と、接続の取り決め（SDP・ICE の候補. 送るのは POST /api/voice/signal、届くのは SSE の voice-signal）を受け渡す.
 * 交渉は perfect negotiation（ぶつかったら connId の小さい側が譲る）. ほかのチャンネルを見ている間もつながったまま.
 */
object Voice {

  /** 入っているボイスチャンネル. */
  val channel: RV[Option[UUID]] = RV(None)
  val joining: RV[Boolean] = RV(false)
  val muted: RV[Boolean] = RV(Prefs.get("voice.muted", "0") == "1")
  val deafened: RV[Boolean] = RV(Prefs.get("voice.deafened", "0") == "1")
  val sharing: RV[Boolean] = RV(false)

  /** マイクを使えない（聞くだけ）. */
  val noMic: RV[Boolean] = RV(false)

  /** ボイスチャンネルごとの、いま入っている人（参加しているチャンネルと、見ている公開チャンネル）. */
  val rooms: RV[Map[UUID, List[VoiceMemberDto]]] = RV(Map.empty)

  /** 話している人（connId）. */
  val speaking: RV[Set[UUID]] = RV(Set.empty)

  /** 相手ごとの接続の状態（RTCPeerConnection の connectionState）. */
  val links: RV[Map[UUID, String]] = RV(Map.empty)

  /** 相手の画面共有（connId → MediaStream）. */
  val screens: RV[Map[UUID, js.Dynamic]] = RV(Map.empty)

  private var conn: Option[UUID] = None
  private var ice: js.Array[js.Any] = js.Array()
  private var mic: Option[js.Dynamic] = None
  private var screen: Option[js.Dynamic] = None
  private var peers: Map[UUID, Peer] = Map.empty
  private var audioEls: Map[String, dom.HTMLMediaElement] = Map.empty
  private var seenSignals: Map[Long, Double] = Map.empty
  private var heartbeat: Option[Int] = None
  private var outbox: Future[Unit] = Future.unit
  private var mutedBeforeDeafen = false

  /** 入った後に始めた読み込みの番号（入る前に始めた読み込みでは、自分がいないことを「外された」と見なさない）. */
  private var reloadSeq = 0
  private var joinedSeq = Int.MaxValue

  def myConn: Option[UUID] = conn
  def members(channelId: UUID): List[VoiceMemberDto] = rooms.v.getOrElse(channelId, Nil)
  def memberOf(connId: UUID): Option[VoiceMemberDto] = rooms.v.values.flatten.find(_.connId == connId)
  def isSelf(m: VoiceMemberDto): Boolean = conn.contains(m.connId)

  /** 入っているチャンネル（一覧から）. */
  def current: CRV[Option[ChannelDto]] = computed(channel.v.flatMap(id => Store.channels.v.find(_.id == id)))

  private def mediaDevices: js.Dynamic = dom.window.navigator.asInstanceOf[js.Dynamic].mediaDevices
  private def tracks(s: js.Dynamic, kind: String): List[js.Dynamic] =
    s.getTracks().asInstanceOf[js.Array[js.Dynamic]].toList.filter(_.kind.asInstanceOf[String] == kind)
  private def promise[A](p: js.Dynamic): Future[A] = p.asInstanceOf[js.Promise[A]].toFuture

  // ---------------------------------------------------------------- 顔ぶれ

  private var reloadTimer: Option[Int] = None

  /** 顔ぶれを読み直す（続けて呼ばれてもまとめて1回）. */
  def reload(): Unit = if (Store.me.v.isDefined && reloadTimer.isEmpty)
    reloadTimer = Some(dom.window.setTimeout(
      () => {
        reloadTimer = None
        reloadSeq += 1
        val seq = reloadSeq
        Api.voiceRooms(Store.viewingId).foreach { rs =>
          rooms := rs.map(r => r.channelId -> r.members).toMap
          sync(seq)
        }
      },
      100
    ))

  /** 顔ぶれに合わせて、相手との接続を作る・閉じる. 自分がいなければ（ほかの画面で入った・切れた）、この画面のボイスを切る. */
  private def sync(seq: Int): Unit = (channel.v, conn) match {
    case (Some(ch), Some(me)) =>
      val ms = members(ch)
      if (!ms.exists(_.connId == me)) {
        if (seq > joinedSeq) dropped("ボイスチャンネルから外れました（ほかの画面で入ったか、接続が切れました）")
      } else {
        val others = ms.map(_.connId).filterNot(_ == me).toSet
        val before = peers.keySet
        peers.keys.filterNot(others.contains).foreach(close)
        others.filterNot(peers.contains).foreach(open)
        if (seq > joinedSeq) {
          if ((others -- before).nonEmpty) Sounds.join()
          if ((before -- others).nonEmpty) Sounds.leave()
        }
      }
    case _ => ()
  }

  // ---------------------------------------------------------------- 入る・出る

  /** 入る（ほかのボイスチャンネルにいれば移る）. */
  def join(channelId: UUID): Unit = if (!channel.v.contains(channelId) && !joining.v) {
    if (channel.v.isDefined) leave(sound = false)
    joining := true
    Store.askDesktopPermission()
    openMic().flatMap { _ =>
      Api.voiceJoin(VoiceJoinReq(channelId, Some(muted.v || noMic.v), Some(deafened.v)))
    }.onComplete {
      case scala.util.Success(j) =>
        joining := false
        conn = Some(j.connId)
        ice = j.iceServers.map(s => js.JSON.parse(s.asJson.noSpaces).asInstanceOf[js.Any]).toJSArray
        channel := Some(channelId)
        rooms := rooms.v.updated(channelId, j.room.members)
        joinedSeq = reloadSeq
        applyMute()
        heartbeat = Some(dom.window.setInterval(() => sendState(), 15000))
        sync(joinedSeq + 1)
        Sounds.join()
        Store.reloadChannels() // 参加していなかった公開チャンネルなら一覧に入る
      case scala.util.Failure(e) =>
        joining := false
        stopMic()
        Toasts.error(e)
    }
  }

  /** マイクを開く（使えなければ聞くだけで入る）. */
  private def openMic(): Future[Unit] =
    if (js.isUndefined(mediaDevices) || js.isUndefined(mediaDevices.getUserMedia)) {
      noMic := true
      Toasts.show("このブラウザではマイクを使えません（聞くだけで参加します）")
      Future.unit
    } else
      promise[js.Dynamic](mediaDevices.getUserMedia(js.Dynamic.literal(
        audio = js.Dynamic.literal(echoCancellation = true, noiseSuppression = true, autoGainControl = true),
        video = false
      ))).map { s =>
        mic = Some(s)
        noMic := false
        watchSpeaking(None, s)
      }.recover { case e =>
        noMic := true
        Toasts.show(s"マイクを使えません（聞くだけで参加します）: ${Option(e.getMessage).getOrElse(e.toString)}", error = true)
      }

  /** 出る. */
  def leave(sound: Boolean = true): Unit = {
    conn.foreach(c => Api.voiceLeave(c).failed.foreach(_ => ()))
    if (sound && conn.isDefined) Sounds.leave()
    cleanup()
  }

  /** 画面を閉じるとき（送り終わる前にページが消えても届くように）. */
  def leaveNow(): Unit = conn.foreach { c => Api.voiceLeaveKeepalive(c); cleanup() }

  private def dropped(msg: String): Unit = {
    cleanup()
    Toasts.show(msg)
  }

  private def cleanup(): Unit = {
    heartbeat.foreach(dom.window.clearInterval); heartbeat = None
    peers.keys.foreach(close)
    stopShareTracks()
    stopMic()
    analysers.keys.foreach(stopSpeaking)
    conn = None
    joinedSeq = Int.MaxValue
    channel := None
    sharing := false
    links := Map.empty
    screens := Map.empty
    speaking := Set.empty
    reload()
  }

  private def stopMic(): Unit = {
    mic.foreach(s => s.getTracks().asInstanceOf[js.Array[js.Dynamic]].foreach(_.stop()))
    mic = None
  }

  // ---------------------------------------------------------------- マイク・スピーカー・画面共有

  def toggleMute(): Unit = if (!noMic.v) {
    muted := !muted.v
    if (!muted.v && deafened.v) deafened := false // 話すならスピーカーも戻す
    remember(); applyMute(); sendState()
  }

  /** スピーカーをオフにする（自分のマイクもオフにする. 戻すとマイクも元の状態に戻す）. */
  def toggleDeafen(): Unit = {
    if (!deafened.v) { mutedBeforeDeafen = muted.v; deafened := true; muted := true }
    else { deafened := false; muted := mutedBeforeDeafen }
    remember(); applyMute(); sendState()
  }

  private def remember(): Unit = {
    Prefs.set("voice.muted", if (muted.v) "1" else "0")
    Prefs.set("voice.deafened", if (deafened.v) "1" else "0")
  }

  private def applyMute(): Unit = {
    mic.foreach(s => tracks(s, "audio").foreach(_.enabled = !muted.v))
    audioEls.values.foreach(_.muted = deafened.v)
  }

  def canShare: Boolean = !js.isUndefined(mediaDevices) && !js.isUndefined(mediaDevices.getDisplayMedia)

  def toggleShare(): Unit = if (sharing.v) stopShare() else startShare()

  private def startShare(): Unit =
    if (!canShare) Toasts.show("このブラウザでは画面共有を使えません", error = true)
    else
      promise[js.Dynamic](mediaDevices.getDisplayMedia(js.Dynamic.literal(
        video = js.Dynamic.literal(frameRate = js.Dynamic.literal(ideal = 30, max = 30)),
        audio = true
      ))).foreach { s =>
        if (conn.isEmpty) s.getTracks().asInstanceOf[js.Array[js.Dynamic]].foreach(_.stop())
        else {
          screen = Some(s)
          // ブラウザの「共有を停止」で止めたとき
          tracks(s, "video").foreach { t =>
            t.contentHint = "detail"
            t.onended = (_: js.Any) => stopShare()
          }
          peers.values.foreach(_.addScreen(s))
          sharing := true
          sendState()
        }
      }

  def stopShare(): Unit = if (sharing.v) {
    stopShareTracks()
    sharing := false
    sendState()
  }

  private def stopShareTracks(): Unit = {
    peers.values.foreach(_.removeScreen())
    screen.foreach(s => s.getTracks().asInstanceOf[js.Array[js.Dynamic]].foreach(_.stop()))
    screen = None
  }

  /** 自分の画面共有（自分の画面に小さく出す）. */
  def ownScreen: Option[js.Dynamic] = screen

  private def sendState(): Unit = conn.foreach { c =>
    Api.voiceState(VoiceStateReq(c, muted.v || noMic.v, deafened.v, sharing.v)).failed.foreach {
      case sbirds.web.ApiError(404, _) => if (conn.contains(c)) dropped("ボイスチャンネルから外れました（ほかの画面で入ったか、接続が切れました）")
      case _ => ()
    }
  }

  // ---------------------------------------------------------------- 取り決め

  /** SSE の voice-signal（同じものが2回届くことがあるので番号で除く）. */
  def onSignal(raw: String): Unit = parse(raw).flatMap(_.as[VoiceSignalDto]).foreach { d =>
    val now = js.Date.now()
    if (!seenSignals.contains(d.id)) {
      seenSignals = seenSignals.filter(_._2 > now - 60000).updated(d.id, now)
      if (conn.contains(d.to)) {
        // まだ顔ぶれに出ていない相手（入った直後）からも受ける
        val p = peers.getOrElse(d.from, open(d.from))
        p.onSignal(d.data)
      }
    }
  }

  /** 送る（順番を保つため、前の送信が終わってから送る）. */
  private def send(to: UUID, data: Json): Unit = conn.foreach { c =>
    outbox = outbox.flatMap(_ => Api.voiceSignal(VoiceSignalReq(c, to, data)).recover { case _ => () })
  }

  private def open(id: UUID): Peer = {
    val p = new Peer(id, polite = conn.exists(_.toString < id.toString))
    peers += id -> p
    p
  }

  private def close(id: UUID): Unit = {
    peers.get(id).foreach(_.close())
    peers -= id
    audioEls.filter(_._1.startsWith(id.toString)).foreach { (k, el) =>
      el.asInstanceOf[js.Dynamic].srcObject = null; el.remove(); audioEls -= k
    }
    analysers.keys.filter(_.startsWith(id.toString)).foreach(stopSpeaking)
    links := links.v - id
    screens := screens.v - id
  }

  private def onRemoteTrack(peer: UUID, track: js.Dynamic, s: js.Dynamic): Unit = {
    val key = s"$peer/${s.id}"
    if (!audioEls.contains(key)) {
      // 音は画面の表示と別の audio 要素で鳴らす（ほかのチャンネルを見ていても聞こえるように）
      val el = dom.document.createElement("audio").asInstanceOf[dom.HTMLMediaElement]
      el.autoplay = true
      el.asInstanceOf[js.Dynamic].srcObject = s
      el.muted = deafened.v
      el.style.display = "none"
      dom.document.body.appendChild(el)
      audioEls += key -> el
    }
    if (track.kind.asInstanceOf[String] == "video") {
      screens := screens.v.updated(peer, s)
      // 相手が共有をやめた
      val gone: js.Function1[js.Any, Unit] = _ =>
        if (screens.v.get(peer).exists(x => tracks(x, "video").forall(_.readyState.asInstanceOf[String] == "ended"))) {
          screens := screens.v - peer
          // 画面の音の要素も片付ける
          audioEls.get(key).foreach { el => el.asInstanceOf[js.Dynamic].srcObject = null; el.remove(); audioEls -= key }
        }
      track.onended = gone
      s.onremovetrack = gone
    } else watchSpeaking(Some(peer), s)
  }

  /** 1人の相手との RTCPeerConnection. */
  private final class Peer(val id: UUID, polite: Boolean) {
    val pc: js.Dynamic =
      js.Dynamic.newInstance(js.Dynamic.global.RTCPeerConnection)(js.Dynamic.literal(iceServers = ice))
    private var makingOffer = false
    private var ignoreOffer = false
    private var chain: Future[Unit] = Future.unit
    private var screenTx: List[js.Dynamic] = Nil

    pc.onnegotiationneeded = (_: js.Any) => {
      makingOffer = true
      promise[Unit](pc.setLocalDescription())
        .map(_ => signal(js.Dynamic.literal(description = pc.localDescription)))
        .recover { case e => dom.console.warn(s"voice: offer failed: $e") }
        .onComplete(_ => makingOffer = false)
    }
    pc.onicecandidate = (e: js.Dynamic) => if (e.candidate != null) signal(js.Dynamic.literal(candidate = e.candidate))
    pc.ontrack = (e: js.Dynamic) =>
      e.streams.asInstanceOf[js.Array[js.Dynamic]].headOption.foreach(s => onRemoteTrack(id, e.track, s))
    pc.onconnectionstatechange = (_: js.Any) => {
      val st = pc.connectionState.asInstanceOf[String]
      links := links.v.updated(id, st)
      if (st == "failed") pc.restartIce()
    }

    // つながらないまま（ICE が始まらない・途中で切れた）なら、譲らない側が ICE をやり直す
    private val watchdog = dom.window.setInterval(
      () => {
        val st = pc.connectionState.asInstanceOf[String]
        if (!polite && st != "connected" && st != "new" && pc.signalingState.asInstanceOf[String] == "stable")
          pc.restartIce()
      },
      10000
    )

    // 自分のマイク（オン・オフはトラックの enabled で切り替え、交渉し直さない）と、共有中の画面を送る
    mic.foreach(s => tracks(s, "audio").foreach(t => pc.addTrack(t, s)))
    screen.foreach(addScreen)

    private def signal(d: js.Dynamic): Unit = parse(js.JSON.stringify(d)).foreach(j => send(id, j))

    /** 相手からの SDP・ICE の候補（順番に処理する）. */
    def onSignal(data: Json): Unit = {
      val d = js.JSON.parse(data.noSpaces).asInstanceOf[js.Dynamic]
      chain = chain.flatMap(_ => handle(d)).recover { case e => dom.console.warn(s"voice: signal failed: $e") }
    }

    private def handle(d: js.Dynamic): Future[Unit] = {
      val desc = d.description
      if (!js.isUndefined(desc) && desc != null) {
        val offer = desc.`type`.asInstanceOf[String] == "offer"
        val collision = offer && (makingOffer || pc.signalingState.asInstanceOf[String] != "stable")
        ignoreOffer = !polite && collision
        if (ignoreOffer) Future.unit
        else
          promise[Unit](pc.setRemoteDescription(desc)).flatMap { _ =>
            if (offer) promise[Unit](pc.setLocalDescription()).map(_ =>
              signal(js.Dynamic.literal(description = pc.localDescription))
            )
            else Future.unit
          }
      } else if (!js.isUndefined(d.candidate) && d.candidate != null)
        promise[Unit](pc.addIceCandidate(d.candidate)).recover { case _ if ignoreOffer => () }
      else Future.unit
    }

    def addScreen(s: js.Dynamic): Unit =
      screenTx = s.getTracks().asInstanceOf[js.Array[js.Dynamic]].toList.map(t => pc.addTrack(t, s))

    def removeScreen(): Unit = {
      screenTx.foreach(tx => Try(pc.removeTrack(tx)))
      screenTx = Nil
    }

    def close(): Unit = { dom.window.clearInterval(watchdog); Try(pc.close()) }
  }

  // ---------------------------------------------------------------- 話している人

  private var audioCtx: Option[js.Dynamic] = None
  private var analysers: Map[String, (js.Dynamic, js.Dynamic)] =
    Map.empty // キー（"self" か connId/streamId）→ (source, analyser)
  private var levelTimer: Option[Int] = None
  private var lastLoud: Map[String, Double] = Map.empty
  private val buf = new js.typedarray.Uint8Array(512)

  private[web] def ctx(): Option[js.Dynamic] = {
    if (audioCtx.isEmpty) audioCtx = Try {
      val C = if (!js.isUndefined(js.Dynamic.global.AudioContext)) js.Dynamic.global.AudioContext
      else js.Dynamic.global.webkitAudioContext
      js.Dynamic.newInstance(C)()
    }.toOption
    audioCtx.foreach(c => if (c.state.asInstanceOf[String] == "suspended") c.resume())
    audioCtx
  }

  private def watchSpeaking(peer: Option[UUID], s: js.Dynamic): Unit = if (tracks(s, "audio").nonEmpty)
    ctx().foreach { c =>
      val key = peer.fold("self")(p => s"$p/${s.id}")
      stopSpeaking(key)
      Try {
        val src = c.createMediaStreamSource(s)
        val an = c.createAnalyser()
        an.fftSize = 512
        src.connect(an)
        analysers += key -> (src, an)
      }
      if (levelTimer.isEmpty) levelTimer = Some(dom.window.setInterval(() => measure(), 150))
    }

  private def stopSpeaking(key: String): Unit = {
    analysers.get(key).foreach((src, _) => Try(src.disconnect()))
    analysers -= key
    if (analysers.isEmpty) { levelTimer.foreach(dom.window.clearInterval); levelTimer = None; lastLoud = Map.empty }
  }

  private def measure(): Unit = {
    val t = js.Date.now()
    analysers.foreach { (key, v) =>
      v._2.getByteTimeDomainData(buf)
      var sum = 0.0
      var i = 0
      while (i < buf.length) { val x = (buf(i) - 128) / 128.0; sum += x * x; i += 1 }
      val who = key.takeWhile(_ != '/')
      if (math.sqrt(sum / buf.length) > 0.04) lastLoud += who -> t
    }
    val now = lastLoud.filter(_._2 > t - 500).keySet.flatMap { k =>
      if (k == "self") conn.filter(_ => !muted.v)
      else Try(UUID.fromString(k)).toOption.filter(c => !memberOf(c).exists(_.muted))
    }
    if (now != speaking.v) speaking := now
  }

  // ---------------------------------------------------------------- 入退室の音

  private object Sounds {
    def join(): Unit = tones(List(523.25, 783.99))
    def leave(): Unit = tones(List(783.99, 523.25))

    private def tones(freqs: List[Double]): Unit = if (!deafened.v && Prefs.get("voice.sound", "on") == "on")
      ctx().foreach { c =>
        Try {
          val t0 = c.currentTime.asInstanceOf[Double]
          freqs.zipWithIndex.foreach { (f, i) =>
            val o = c.createOscillator()
            val g = c.createGain()
            o.frequency.value = f
            val st = t0 + i * 0.09
            g.gain.setValueAtTime(0.0001, st)
            g.gain.exponentialRampToValueAtTime(0.08, st + 0.02)
            g.gain.exponentialRampToValueAtTime(0.0001, st + 0.16)
            o.connect(g); g.connect(c.destination)
            o.start(st); o.stop(st + 0.18)
          }
        }
      }
  }
}
