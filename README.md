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

## Sending events

    http 'localhost:8800/message/ninjudd:jazzhands' 'message:={"x":1}'

## License

Distributed under the Eclipse Public License, the same as Clojure.
