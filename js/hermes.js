
function Hermes(opts){

  var self = this;
  this.initialize = function(opts){
    this.server               = opts.server;
    this.namespace            = opts.namespace || '';
    this.hermesPrepend        = "hermes-msg:"
    this.unboundSubscriptions = {};

    this.ws           = new WebSocket(this.server);
    this.ws.onmessage = this.onServerMessage;
    this.ws.onopen    = this.onConnectionOpen;
    this.ws.onclose   = this.onConnectionClose;
   }

  this.onConnectionOpen = function(){
    for (var s in self.unboundSubscriptions) {
      self.ws.send(s);
    }
  }

  this.onConnectionClose = function(){
    if ( console )
      console.log("[HERMES] Connection closed.")
  }

  this.onServerMessage = function(e){
    if (e.data == '')
        return;

    var msg = JSON.parse(e.data);
    msg.event = e; 
    HermesEvents.publish( self.hermesPrepend + msg.subscription, [msg])
  }

  this.subscribe = function(topic, absolute, callback, name){
    // absolute is optional, bypasses namespace
    topic     = (absolute != null && callback != null) ? topic : this.namespace + topic;
    callback  = callback || absolute;
    name      = name || "default";

    if ( this.ws.readyState !== 1 ){
      this.unboundSubscriptions[topic] = 1;
    }
    else {
      this.ws.send(topic);
    }

    HermesEvents.subscribe( this.hermesPrepend + topic, name, callback);
  }

  this.initialize(opts);
}

// Simple Hermes PUBSUB Helper.
window.HermesEvents = (function (){
  var cache = {},

  publish = function (topic, args, scope) {
    if ( cache[topic] ) {
      for(var name in cache[topic]){
        cache[topic][name].apply( scope || this, args || []);
      }
    }
  },

  subscribe = function (topic, name, callback) {
    if ( !cache[topic] )
        cache[topic] = {};

    if ( cache[topic][name] )
      cache[topic][name] = null;

    cache[topic][name] = callback;
    return [topic, name, callback];
  }

  return {
    publish: publish,
    subscribe: subscribe,
    cache: cache
  };

}());
