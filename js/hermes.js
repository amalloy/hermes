
function Hermes(opts){

  var self = this;
  this.initialize = function(opts){
    this.server               = opts.server;
    this.namespace            = opts.key || '';
    this.subscriptions        = {};
    this.unboundSubscriptions = {};

    this.ws               = new WebSocket("ws://" + this.server);
    this.ws.onmessage     = this.onServerMessage;
    this.ws.onopen        = this.onConnectionOpen;
  }

  this.onConnectionOpen = function(){
    for (var s in self.unboundSubscriptions) {
      self.subscriptions[s] = true;
      self.ws.send(s);
    }
  }

  this.onServerMessage = function(e){
    var msg = JSON.parse(e.data);
    msg.event = e; 
    HermesEvents.publish( "hermes-msg:" + msg.topic, [msg])
  }

  this.subscribe = function(topic, callback){
    if ( this.ws.readyState !== 1 ){
      if ( !this.unboundSubscriptions[topic] ){
        this.unboundSubscriptions[topic] = true;
      }
    }
    else if ( !this.subscriptions[topic] ) {
      this.subscriptions[topic] = true;
      this.ws.send( this.namespace + topic );
    }

    HermesEvents.subscribe("hermes-msg:" + topic, callback) 
  }

  this.initialize(opts);
}

// Simple Hermes PUBSUB Helper.
window.HermesEvents = (function (){
  var cache = {},

  publish = function (topic, args, scope) {
    if (cache[topic]) {
      var thisTopic = cache[topic],
        i = thisTopic.length - 1;

      for (i; i >= 0; i -= 1) {
        thisTopic[i].apply( scope || this, args || []);
      }
    }
  },

  subscribe = function (topic, callback) {
    if (!cache[topic]) {
      cache[topic] = [];
    }
    cache[topic].push(callback);
    return [topic, callback];
  },

  unsubscribe = function (handle, completly) {
    var t = handle[0],
      i = cache[t].length - 1;

    if (cache[t]) {
      for (i; i >= 0; i -= 1) {
        if (cache[t][i] === handle[1]) {
          cache[t].splice(cache[t][i], 1);
          if(completly){ delete cache[t]; }
        }
      }
    }
  };

  return {
    publish: publish,
    subscribe: subscribe,
    unsubscribe: unsubscribe
  };

}());