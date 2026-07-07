import { Client } from '@stomp/stompjs'

/**
 * STOMP 客户端封装:
 *  - 自动重连 (指数退避 1s → 2s → 4s → 8s → 16s → 32s, 上限 32s)
 *  - 重连后自动恢复所有订阅
 *  - 提供手动 disconnect / subscribe
 */
export class StompClient {
  constructor({ token, onConnected, onDisconnected, onError, onMessage } = {}) {
    this.token = token
    this.onConnected = onConnected
    this.onDisconnected = onDisconnected
    this.onError = onError
    this.onMessage = onMessage
    this.client = null
    this.subscribed = new Set()
    this.reconnectAttempt = 0
    this.maxBackoff = 32000
    this._intentionallyClosed = false
  }

  connect(path = '/ws/customer') {
    this._intentionallyClosed = false
    const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws'
    const brokerURL = `${wsProtocol}://${location.host}${path}?token=${encodeURIComponent(this.token)}`
    this.client = new Client({
      // @stomp/stompjs v6+ 用 brokerURL (v5 及以前是 url)
      brokerURL,
      // STOMP 库自带的 reconnectDelay 也设一下, 但我们自己用指数退避
      reconnectDelay: this._nextBackoff(),
      heartbeatIncoming: 20000,
      heartbeatOutgoing: 20000,
      debug: () => {}
    })
    this.client.onConnect = () => {
      this.reconnectAttempt = 0
      this.onConnected?.()
      this.subscribed.forEach(({ destination, cb }) => this._doSubscribe(destination, cb))
    }
    this.client.onWebSocketClose = () => {
      this.onDisconnected?.()
      if (!this._intentionallyClosed) {
        const delay = this._nextBackoff()
        if (this.client) this.client.configure({ reconnectDelay: delay })
        this.reconnectAttempt++
      }
    }
    this.client.onStompError = (frame) => {
      console.error('[stomp] error', frame.headers['message'], frame.body)
      this.onError?.(frame)
    }
    this.client.activate()
  }

  _nextBackoff() {
    // 1s, 2s, 4s, 8s, 16s, 32s (上限)
    const d = Math.min(this.maxBackoff, 1000 * Math.pow(2, this.reconnectAttempt))
    return d
  }

  subscribe(destination, cb) {
    this.subscribed.add({ destination, cb })
    if (this.client?.connected) {
      return this._doSubscribe(destination, cb)
    }
    return null
  }

  _doSubscribe(destination, cb) {
    return this.client.subscribe(destination, (msg) => {
      let body
      try { body = JSON.parse(msg.body) } catch { body = msg.body }
      cb ? cb(body, msg) : this.onMessage?.(body, msg)
    })
  }

  send(destination, payload) {
    if (!this.client?.connected) {
      console.warn('[stomp] not connected, drop message:', destination)
      return false
    }
    this.client.publish({ destination, body: JSON.stringify(payload) })
    return true
  }

  disconnect() {
    this._intentionallyClosed = true
    this.subscribed.clear()
    this.client?.deactivate()
    this.client = null
  }
}