/**
 * webrtc-sdk.js - WebRTC 1v1 视频通话客户端.
 * ----------------------------------------------------------------------------
 * 封装 RTCPeerConnection + getUserMedia + STOMP 信令, 提供开箱即用 API.
 *
 * 用法:
 *   const call = new VideoCall({
 *     sessionId, localUid, peerUid, peerName,
 *     iceServers, stompClient,  // 复用 cs-im 的 StompClient
 *     onRemoteStream, onStateChange, onError,
 *   })
 *   await call.startLocalCamera()
 *   await call.call()    // A 主动
 *   // 或 call.answer() // B 收到 offer 后
 *   call.hangup()
 */
import { StompClient } from './ws-client'

export class VideoCall {
  constructor(opts) {
    this.sessionId = opts.sessionId       // 视频会话 ID
    this.localUid = opts.localUid
    this.peerUid = opts.peerUid
    this.peerName = opts.peerName || '对端'
    this.iceServers = opts.iceServers || [{ urls: 'stun:stun.l.google.com:19302' }]
    this.stomp = opts.stompClient         // 复用 cs-im 的 StompClient (已连)
    this.onRemoteStream = opts.onRemoteStream || (() => {})
    this.onStateChange = opts.onStateChange || (() => {})
    this.onError = opts.onError || ((e) => console.error(e))
    this.localStream = null
    this.peerConnection = null
    this.state = 'IDLE'                  // IDLE/CALLING/INCOMING/CONNECTING/CONNECTED/ENDED
  }

  // ============ 本地摄像头 ============

  /**
   * 开启本地摄像头 + 麦克风.
   * 返回 MediaStream (挂到 <video autoplay muted>).
   */
  async startLocalCamera() {
    try {
      this.localStream = await navigator.mediaDevices.getUserMedia({
        video: { width: 1280, height: 720, frameRate: 25 },
        audio: { echoCancellation: true, noiseSuppression: true },
      })
      this._emitState('LOCAL_READY')
      return this.localStream
    } catch (e) {
      this.onError(e)
      throw e
    }
  }

  /** 关闭本地摄像头 */
  stopLocalCamera() {
    if (this.localStream) {
      this.localStream.getTracks().forEach(t => t.stop())
      this.localStream = null
    }
  }

  // ============ 静音 / 关摄像头 ============

  toggleMic(on) {
    if (!this.localStream) return
    this.localStream.getAudioTracks().forEach(t => t.enabled = on !== false)
    this._sendMedia('mic', on !== false)
  }
  toggleCamera(on) {
    if (!this.localStream) return
    this.localStream.getVideoTracks().forEach(t => t.enabled = on !== false)
    this._sendMedia('camera', on !== false)
  }

  // ============ 发起 / 接听 / 挂断 ============

  /**
   * A 主动发起: createOffer -> 发送.
   */
  async call() {
    if (!this.localStream) await this.startLocalCamera()
    this._createPeer()
    this._subscribeSignaling()
    this._emitState('CALLING')

    const offer = await this.peerConnection.createOffer({
      offerToReceiveAudio: true,
      offerToReceiveVideo: true,
    })
    await this.peerConnection.setLocalDescription(offer)
    this._sendSignal('offer', { sdp: offer.sdp, type: offer.type })
  }

  /**
   * B 收到 A 的 offer 后调用: setRemote + createAnswer.
   * @param offerSdp A 发送的 SDP
   */
  async answer(offerSdp) {
    if (!this.localStream) await this.startLocalCamera()
    this._createPeer()
    this._subscribeSignaling()
    this._emitState('CONNECTING')

    await this.peerConnection.setRemoteDescription(
      new RTCSessionDescription({ type: 'offer', sdp: offerSdp }))
    const answer = await this.peerConnection.createAnswer()
    await this.peerConnection.setLocalDescription(answer)
    this._sendSignal('answer', { sdp: answer.sdp, type: answer.type })
  }

  /** 挂断 */
  hangup() {
    this._sendSignal('hangup', {})
    this._teardown()
    this._emitState('ENDED')
  }

  // ============ 内部 ============

  _createPeer() {
    this.peerConnection = new RTCPeerConnection({ iceServers: this.iceServers })
    // 本地流加到 peer
    if (this.localStream) {
      this.localStream.getTracks().forEach(t =>
        this.peerConnection.addTrack(t, this.localStream))
    }
    // 监听远端流
    this.peerConnection.ontrack = (e) => {
      const [stream] = e.streams
      this.onRemoteStream(stream)
      this._emitState('CONNECTED')
    }
    // ICE 候选
    this.peerConnection.onicecandidate = (e) => {
      if (e.candidate) {
        this._sendSignal('ice', { candidate: e.candidate.toJSON() })
      }
    }
    // 连接状态
    this.peerConnection.onconnectionstatechange = () => {
      const s = this.peerConnection.connectionState
      if (s === 'failed' || s === 'disconnected' || s === 'closed') {
        this._teardown()
        this._emitState('ENDED')
      }
    }
  }

  _subscribeSignaling() {
    if (!this.stomp) return
    // 订阅来自对端的信令
    this.stomp.subscribe(`/user/queue/video/${this.sessionId}/offer`,  (m) => this._onRemoteOffer(m))
    this.stomp.subscribe(`/user/queue/video/${this.sessionId}/answer`, (m) => this._onRemoteAnswer(m))
    this.stomp.subscribe(`/user/queue/video/${this.sessionId}/ice`,    (m) => this._onRemoteIce(m))
    this.stomp.subscribe(`/user/queue/video/${this.sessionId}/hangup`, () => {
      this._teardown()
      this._emitState('ENDED')
    })
    this.stomp.subscribe(`/user/queue/video/${this.sessionId}/media`,  (m) => this._onRemoteMedia(m))
  }

  async _onRemoteOffer(m) {
    if (this.state === 'CONNECTED' || this.state === 'CALLING') return  // 防重
    const { sdp } = m.body || m
    if (sdp) await this.answer(sdp)
  }

  async _onRemoteAnswer(m) {
    const { sdp } = m.body || m
    if (sdp && this.peerConnection) {
      await this.peerConnection.setRemoteDescription(
        new RTCSessionDescription({ type: 'answer', sdp }))
    }
  }

  async _onRemoteIce(m) {
    const cand = (m.body || m).candidate
    if (cand && this.peerConnection) {
      try {
        await this.peerConnection.addIceCandidate(new RTCIceCandidate(cand))
      } catch (e) { /* 可能重复, 忽略 */ }
    }
  }

  _onRemoteMedia(m) {
    // 透传: 父组件可订阅做 UI 提示 (对端关了摄像头)
    this.onMediaChange?.(m.body || m)
  }

  _sendSignal(type, payload) {
    if (!this.stomp || !this.stomp.client?.connected) return
    const dest = `/app/video/${this.sessionId}/${type}`
    const body = { from: this.localUid, to: this.peerUid, ...payload }
    this.stomp.client.publish({
      destination: dest,
      body: JSON.stringify(body),
    })
  }

  _sendMedia(action, on) {
    this._sendSignal('media', { action, on })
  }

  _teardown() {
    if (this.peerConnection) {
      this.peerConnection.close()
      this.peerConnection = null
    }
    this.stopLocalCamera()
  }

  _emitState(state) {
    this.state = state
    this.onStateChange(state)
  }
}