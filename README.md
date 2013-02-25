# hermes

A dead simple service for pushing events to a web browser.

## Usage - Server

    brew install leiningen
    lein run

## Usage - Client

```javascript
var h = new Hermes({ server: 'localhost:8008' });
h.subscribe('ninjudd:jazzhands', function(e){
  // Do something with the payload...
  console.log(e.data)
})
```

Hermes takes an optional `key` parameter at instantiation time, which can be used for additional security:
```javascript
var h = new Hermes({ server: 'localhost:8008', key: 'my-secret-key' });
```


## Sending events

    curl -v -H "Content-Type: application/json" -X PUT -d '{"num":1}' 'localhost:2960/message/inbox-count'

# Examples

1. Start up your lein server 
2. In root of the Hermes repo, fire up a little server to serve our example files:

```shell
python -m SimpleHTTPServer
```

3. Point your browser to `http://localhost:8000/examples/`
4. Send an event: `curl -v -H "Content-Type: application/json" -X PUT -d '{"num":1}' 'localhost:2960/message/inbox-count`

# Websockets Polyfill
A note on [using the Flash polyfill for Websockets](https://github.com/flatland/hermes/wiki/Websocket-Polyfill). 

## License

Distributed under the Eclipse Public License, the same as Clojure.
