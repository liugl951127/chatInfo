import { Client } from '@stomp/stompjs'

/**
 * STOMP 客户端封装。
 * <p>
 * 用法:
 *   const client = new StompClient({ token, onMessage })
 *   client.connect('/ws/customer')
 *   client.send(`/app/send/${sessionId}`, { ...payload })
 *   client.disconnect()
 */
export class StompClient {
  constructor({ token, onMessage, onConnected, onDisconnected, onError } = {}) {
    this.token = token
    this.onMessage = onMessage
    this.onConnected = onConnected
    this.onDisconnected = onDisconnected
    this.onError = onError
    this.client = null
    this.subscribed = new Set()
  }

  connect(path = '/ws/customer') {
    const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}${path}?token=${encodeURIComponent(this.token)}`
    this.client = new Client({
      url,
      reconnectDelay: 3000,
      heartbeatIncoming: 20000,
      heartbeatOutgoing: 20000,
      debug: () => {}
    })
    this.client.onConnect = () => {
      this.onConnected?.()
      // 重新订阅
      this.subscribed.forEach(({ destination, cb }) => this._doSubscribe(destination, cb))
    }
    this.client.onWebSocketClose = (evt) => this.onDisconnected?.(evt)
    this.client.onStompError = (frame) => {
      console.error('[stomp] error', frame.headers['message'], frame.body)
      this.onError?.(frame)
    }
    this.client.activate()
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
      try {
        const body = JSON.parse(msg.body)
        (cb || this.onMessage)?.(body, msg)
      } catch (e) {
        (cb || this.onMessage)?.(msg.body, msg)
      }
    })
  }

  send(destination, payload) {
    if (!this.client?.connected) {
      console.warn('[stomp] not connected, drop message')
      return
    }
    this.client.publish({
      destination,
      body: JSON.stringify(payload)
    })
  }

  disconnect() {
    this.subscribed.clear()
    this.client?.deactivate()
    this.client = null
  }
}