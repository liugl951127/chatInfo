import { Client } from '@stomp/stompjs'

/**
 * STOMP 客户端封装.
 *
 * 设计要点:
 *  - subscribe() 幂等: 同一 destination+callback 组合只订阅一次, 防双订阅导致消息双发
 *  - onConnected 回调只在首次连接触发一次, 用于 UI 状态更新
 *  - 重连后自动从 subscribed Set 重新订阅所有跟踪的目的地
 *  - 指数退避重连 (1s → 32s 上限)
 */
export class StompClient {
  constructor({ token, onConnected, onDisconnected, onError } = {}) {
    this.token = token
    this.onConnected = onConnected
    this.onDisconnected = onDisconnected
    this.onError = onError
    this.client = null
    this.subscribed = new Set()
    this.reconnectAttempt = 0
    this.maxBackoff = 32000
    this._intentionallyClosed = false
    this._firstConnected = false
  }

  connect(path = '/ws/customer') {
    this._intentionallyClosed = false
    const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws'
    const brokerURL = `${wsProtocol}://${location.host}${path}?token=${encodeURIComponent(this.token)}`
    this.client = new Client({
      brokerURL,
      reconnectDelay: this._nextBackoff(),
      heartbeatIncoming: 20000,
      heartbeatOutgoing: 20000,
      debug: () => {}
    })
    this.client.onConnect = () => {
      this.reconnectAttempt = 0
      // 重连后, 把 Set 里所有目的地重新订阅 (旧订阅在断连时已失效)
      // 因为 subscribe() 幂等, 首次连接时也不重复
      this.subscribed.forEach(({ destination, cb }) => this._doSubscribe(destination, cb))
      // 仅首次触发 UI 的 onConnected (用于状态更新, 不要在里面做订阅!)
      if (!this._firstConnected) {
        this._firstConnected = true
        this.onConnected?.()
      }
    }
    this.client.onWebSocketClose = () => {
      this.onDisconnected?.()
      if (!this._intentionallyClosed && this.client) {
        this.client.configure({ reconnectDelay: this._nextBackoff() })
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
    return Math.min(this.maxBackoff, 1000 * Math.pow(2, this.reconnectAttempt))
  }

  /**
   * 订阅 (幂等).
   * 同一 destination + 同一 callback 函数只会订阅一次.
   * 订阅前调用: 也会记录到 Set, 在 connect / reconnect 时自动激活.
   */
  subscribe(destination, cb) {
    for (const e of this.subscribed) {
      if (e.destination === destination && e.cb === cb) {
        return null  // 已订阅, 跳过
      }
    }
    this.subscribed.add({ destination, cb })
    if (this.client?.connected) {
      return this._doSubscribe(destination, cb)
    }
    return null
  }

  /** 取消订阅 (会话切换时清掉旧的 typing 订阅) */
  unsubscribe(destination, cb) {
    for (const e of this.subscribed) {
      if (e.destination === destination && e.cb === cb) {
        this.subscribed.delete(e)
        break
      }
    }
    // 注: STOMP subscription 对象我们没存, 这里仅从 Set 移除, 重连时不会重新订阅
    // 如果需要真正取消 STOMP 层的订阅, 需要把 _doSubscribe 的返回值保存下来
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
    this._firstConnected = false
    this.subscribed.clear()
    this.client?.deactivate()
    this.client = null
  }
}