# Sliver

A Clojure library designed to make it easy to simulate native Erlang nodes.

## Include it

Add `[sliver "0.0.1"]` if you're working with Leiningen. Alternatively, add

````
    <dependency>
        <groupId>sliver</groupId>
        <artifactId>sliver</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </dependency>
````

if you're using Maven.

## Use it

There's two parts for using `sliver`: nodes and handlers. As expected, a node
will use handlers to handle incoming messages.

First require all the relevant namespaces. In this example just the node
operations and logging are required:

````clojure
    (ns your-spiffy-new-node.core
      (:require [sliver.node :as n]
                [taoensso.timbre :as timbre]))
````

Next, create a handler for the incoming messages:

````clojure
    (defn log-handler [node from to message]
      (timbre/info (str from "->" to " :: " message " :: " other)))
````

the signature of the handler should be fairly self-explanatory. The handler will
get a reference to the node so that it can perform other operations such as
sending messages to the remote erlang node. For instance, an `echo` handler might
look like:

````clojure
    (defn- echo-handler [node from to message]
      (let [[pid m] message]
        (n/send-message node pid m)))
````

Now, create the node with the handlers:

````clojure
    (n/node "foo@127.0.0.1" "monster" [log-handler echo-handler])
````

and finally connect to a remote node:

````clojure
    (n/connect node {:node-name "bar"}) ; returns the node
````


A few things should be noted here:

1. Creating a node is not the same as connecting to a remote node. Nodes can exists in isolation, though they're not good for much on their own (for now).
2. Connecting to a remote node takes its name as parameter. This bit is still in flux since I don't quite like the `{:node-name "bar"}` syntax. Additionally, the name must match what's returned by running `epmd -names`, i.e. `sliver` will talk to `epmd` to figure out the port of the remote node.
3. Creating a node takes a collection of handlers. I wasn't sure whether to go for a `ring` style of middleware or a _streaming_ (sort of) model. I went for the latter.

## Putting it all together

First we Spin up a native Erlang node like:

````shell
    $ erl -name bar@127.0.0.1
    Erlang/OTP 17 [erts-6.4] [source] [64-bit] [smp:4:4] [async-threads:10] [hipe] [kernel-poll:false] [dtrace]

    Eshell V6.4  (abort with ^G)
    (bar@127.0.0.1)1>
````

Next, in a `repl`, define a logging handler:

````clojure
    user> (ns example.sliver)
    nil
    example.sliver> (require '(taoensso [timbre :as timbre]))
    nil
    example.sliver> (defn log-handler [node from to message] (timbre/info (str from "->" to " :: " message)))
    #'example.sliver/log-handler
````

and an echo handler:

````clojure
    example.sliver> (require '(sliver [node :as n]))
    nil
    example.sliver> (defn echo-handler [node from to message]
                      (let [[pid m] message]
                        (n/send-message node pid m)))
    #'example.sliver/echo-handler
````

notice that the echo handler assumes that the messages will be a tuple/sequence
of `pid` and `message`. This protocol is to be respected between your native
Erlang node so that messages can be echoed back.

Now create and connect the node:

````clojure
    example.sliver> (def node (n/node "foo@127.0.0.1" "monster" [log-handler echo-handler]))
    #'example.sliver/node

    example.sliver> (n/connect node {:node-name "bar"})

    2015-Jul-23 09:32:36 +0100 salad-fingers.local DEBUG [sliver.handshake] - PACKET: #<HeapByteBuffer java.nio.HeapByteBuffer[pos=5 lim=5 cap=5]>
    2015-Jul-23 09:32:36 +0100 salad-fingers.local DEBUG [sliver.handshake] - HS-PACKET: #<HeapByteBuffer java.nio.HeapByteBuffer[pos=3 lim=3 cap=3]>
    2015-Jul-23 09:32:36 +0100 salad-fingers.local DEBUG [sliver.handshake] - DECODED:  :ok
    2015-Jul-23 09:32:36 +0100 salad-fingers.local DEBUG [sliver.handshake] - PACKET: #<HeapByteBuffer java.nio.HeapByteBuffer[pos=26 lim=26 cap=26]>
    2015-Jul-23 09:32:36 +0100 salad-fingers.local DEBUG [sliver.handshake] - HS-PACKET: #<HeapByteBuffer java.nio.HeapByteBuffer[pos=24 lim=24 cap=24]>
    2015-Jul-23 09:32:36 +0100 salad-fingers.local DEBUG [sliver.handshake] - DECODED:  {:version 5, :flag 229372, :challenge 1780990473, :name bar@127.0.0.1}
    2015-Jul-23 09:32:36 +0100 salad-fingers.local DEBUG [sliver.handshake] - PACKET: #<HeapByteBuffer java.nio.HeapByteBuffer[pos=19 lim=19 cap=19]>
    2015-Jul-23 09:32:36 +0100 salad-fingers.local DEBUG [sliver.handshake] - HS-PACKET: #<HeapByteBuffer java.nio.HeapByteBuffer[pos=17 lim=17 cap=17]>
    2015-Jul-23 09:32:36 +0100 salad-fingers.local DEBUG [sliver.handshake] - DECODED:  :ok
    #sliver.node.Node{:node-name "foo@127.0.0.1", :cookie "monster", :handlers [#<sliver$log_handler example.sliver$log_handler@15f7bf39> #<sliver$echo_handler example.sliver$echo_handler@3c17aae1>], :state #<Atom@35d3b899: {{:node-name "bar"} {:connection #<SocketChannelImpl java.nio.channels.SocketChannel[connected local=/127.0.0.1:57217 remote=localhost/127.0.0.1:57104]>}}>, :pid-tracker #<Ref@5efc606a: {:creation 0, :serial 0, :pid 0}>}
    example.sliver> 
````

you'll see a bunch of `DEBUG` messages. That's fine (I hope!).

If you did nothing at this point, every minute that passes you'll see a message
like:

````
    2015-Jul-23 09:33:11 +0100 salad-fingers.local INFO [sliver.protocol] - :tock
````

that's the hearbeat from the remote node. Every minute that goes on, if there's
been no data transmission between the nodes, the native Erlang node will ping
(or `tick` if you prefer).

After much prelude, on to the good stuff: seeing if this thing actually works.

In the Erlang `remsh` send a message to the `sliver` node:

````
    (bar@127.0.0.1)5> {ignored, 'foo@127.0.0.1'} ! [self(), hello_world].
    [<0.38.0>,hello_world]
````

you should see something like

````
    2015-Jul-23 09:36:49 +0100 salad-fingers.local INFO [example.sliver] - borges.type.Pid@97b48c4e->ignored :: (#borges.type.Pid{:node bar@127.0.0.1, :pid 38, :serial 0, :creation 3} hello_world)
    2015-Jul-23 09:36:49 +0100 salad-fingers.local INFO [sliver.protocol] - Sent 57 bytes
````

in your Clojure `repl`. If you now flush the `remsh` process you should get your
message back:

````
    (bar@127.0.0.1)6> flush().
    Shell got hello_world
    ok
````

Huzzah!

## Develop it

Please send pull requests my way. You can run all tests with `lein test`.

## License

Copyright © 2015 Ulises Cervi~no Beresi

Distributed under the MIT License.
