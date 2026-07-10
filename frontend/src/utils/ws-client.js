import { Client } from '@stomp/stompjs'

/**
 * StompClient - STOMP 客户端封装 (@stomp/stompjs v7).
 * ----------------------------------------------------------------------------
 * 职责:
 *   - 连接/重连/重订阅封装
 *   - subscribe() 幂等, 防双订阅导致消息双发
 *   - onConnected / onReconnected / onDisconnected 回调
 *
 * 设计要点:
 *   - subscribe() 幂等: 同一 destination+callback 组合只订阅一次
 *   - onConnected 只在首次连接触发 (UI 状态: 加载会话列表)
 *   - onReconnected 每次重连成功后触发 (拉历史/补漏状态, 手机切网场景)
 *   - 重连后自动从 subscribed Set 重新订阅所有目的地
 *   - 指数退避重连 (1s → 32s 上限)
 *
 * API:
 *   - connect(): 启动 STOMP 客户端
 *   - disconnect(): 手动断开 (设 _intentionallyClosed 防误重连)
 *   - subscribe(dest, callback): 幂等订阅
 *   - unsubscribe(dest, callback): 取消订阅
 *   - publish(dest, body, headers?): 发送消息
 *
 * brokerURL: 默认 ws://host/api/im/ws, 携带 ?token=xxx 用于鉴权
 */
export class StompClient {
  constructor({ token, onConnected, onReconnected, onDisconnected, onError } = {}) {
    this.token = token
    this.onConnected = onConnected
    this.onReconnected = onReconnected
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
      this.subscribed.forEach(({ destination, cb }) => this._doSubscribe(destination, cb))
      if (!this._firstConnected) {
        this._firstConnected = true
        this.onConnected?.()
      } else {
        // 重连成功 — 给 UI 一个机会做兜底 (拉历史补漏/刷新状态)
        this.onReconnected?.()
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

  unsubscribe(destination, cb) {
    for (const e of this.subscribed) {
      if (e.destination === destination && e.cb === cb) {
        this.subscribed.delete(e)
        break
      }
    }
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