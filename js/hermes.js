var ws = new WebSocket("ws://ifrit:8008/")
var callbacks = {}

ws.onopen = function() {
  console.log("WebSockets connection opened")
}
ws.onmessage = function(e) {
  msg = JSON.parse(e.data)
  _.each(callbacks[msg.subscription], function (callback) {
    callback(msg.topic, msg.data)
  })
}
ws.onclose = function() {
  console.log("WebSockets connection closed")
}

function subscribe(topic, callback) {
  if (callbacks[topic] == null) {
    ws.send(topic)
  }
  callbacks[topic] = callbacks[topic] || []
  callbacks[topic].push(callback)
}
