# Sliver

[![Build Status](https://travis-ci.org/ulises/sliver.svg?branch=master)](https://travis-ci.org/ulises/sliver)

A Clojure library that simulates an Erlang node.

## Include it

Add `[sliver "0.0.2-SNAPSHOT"]` if you're working with Leiningen. Alternatively, add

````
    <dependency>
        <groupId>sliver</groupId>
        <artifactId>sliver</artifactId>
        <version>0.0.2-SNAPSHOT</version>
    </dependency>
````

if you're using Maven.

## Use it

First create a node. Require the relevant namespaces you'll be using through this document before anything though:

````clojure
    (ns your-spiffy-new-node.core
        (:require [sliver.node :as n]
                  [sliver.primitive :as p]
                  [co.paralleluniverse.pulsar.actors :as a]))
````

Now you can create the node. In the `repl`:

````clojure
    your-spiffy-new-node.core> (def foo-node (n/node "foo@127.0.0.1" "monster"))
    2015-Nov-16 20:52:13 +0000 salad-fingers DEBUG [sliver.node] - foo :: 127.0.0.1
    2015-Nov-16 20:52:13 +0000 salad-fingers DEBUG [sliver.node] - Registering  #borges.type.Pid{:node foo@127.0.0.1, :pid 0, :serial 0, :creation 0}  as  _dead-processes-reaper
    #'your-spiffy-new-node.core/foo-node
    your-spiffy-new-node.core>
````

A few things should be noted here:

1. You will get a bunch of `DEBUG` messages. `sliver` is being heavily developed at the moment, I need all the help I can get
2. The order of these messages might vary from the example up here. That's because they happen asynchronously. Don't worry about it.

After creating a node, there's a few things you can do with it:

- you can `connect` it to another node (an `erlang` or `sliver` node)
- you can `spawn` processes
- you can send (`!`) messages to processes (local or remote)
- you can `start` the node: the node will register with `EPMD` (but won't start it if it's not running), and start listening for incoming connections

There's other things you can do, like `link` or `monitor` processes, but we
won't cover that here.

### `spawn`ing processes

Spawning a new process will return its `pid`. This is an `erlang` pid, so it
can be used both in `erlang` interop, as well as sending messages to other
`sliver` processes.

After you've created the node, spawning a new process is done like so:

````clojure
    your-spiffy-new-node.core> (p/spawn foo-node #(println "I don't do much.")) ;; returns the pid
    I don't do much.
    #borges.type.Pid{:node foo@127.0.0.1, :pid 1, :serial 0, :creation 0}
    your-spiffy-new-node.core>
````

The process spawned will execute the provided thunk (there's currently no
support for spawning functions that take arguments) until it finishes. Under
the hood, these are nothing but plain `pulsar` processes.

Unless you're really curious, you shouldn't bother much with the underlying
structure of pids.

As expected, you can spawn as many processes as you like (all credit goes to
the people who wrote `Pulsar/Quasar`):

````clojure
    your-spiffy-new-node.core> (last (for [n (range 100000)] (p/spawn foo-node #(+ 1 1))))
    #borges.type.Pid{:node foo@127.0.0.1, :pid 213433, :serial 1, :creation 0}
    your-spiffy-new-node.core> 
````

Granted, these processes did nothing much, but try starting 100k threads on a laptop and see how well it fares.

### Sending messages

With `!` (in the `sliver.node-interface` namespace) you can send messages to:

- a `pid`: this is a process' identifier; a handle for when the process is not registered. A `pid` can be local to the `sliver` node, or remote (from an `erlang` or a `sliver` node)
- a named process: this is the name (I prefer `symbols` or `keywords` though any object should be good to be used as a name) under which the process is registered. Names are local to the `sliver` node
- name, node adress pairs, e.g. `[name "node@IP"]`: this is the equivalent to `erlang`'s `{name, node@ip} ! message`. The message will be sent to the registered process in the specified remote node

#### Local messages

Let's send a message to a local unregistered processes:

````clojure
    your-spiffy-new-node.core> (def pid1 (p/spawn foo-node #(a/receive m (prn m))))
    #'your-spiffy-new-node.core/pid1
    your-spiffy-new-node.core> (p/! foo-node pid1 'ohai)
    nil
    ohai
    your-spiffy-new-node.core> 
````

The code above:

1. `spawn`ed a process that waits for a message (any message), and prints it to console
2. sent a message (`'ohai'`) to the `pid` of that process

Let's try the same thing, but with registered processes:

````clojure
    your-spiffy-new-node.core> (def pid1 (p/spawn
                                           foo-node
                                           #(do
                                             (p/register foo-node
                                                          'a-process
                                                          (p/self foo-node))
                                             (a/receive m (prn m)))))
    #'your-spiffy-new-node.core/pid1
    2015-Nov-16 21:09:50 +0000 salad-fingers DEBUG [sliver.node] - Registering  #borges.type.Pid{:node foo@127.0.0.1, :pid 213435, :serial 1, :creation 0}  as  a-process
    your-spiffy-new-node.core> (p/! foo-node 'a-process 'ohai)
    nil
    ohai
    your-spiffy-new-node.core> 
````

The code above is identical to the first example with the exception that the
process registers itself via `p/register` and that we send (`!`) the message
to the process' name and not its `pid`.

Neat.

However, so far you've not done anything we couldn't have done with plain
`pulsar` actors. Let's talk about sending messages to remote processes.

#### Remote messages

`sliver` can send messages to remote nodes. To demonstrate this, you could use
a second `sliver` node, but that wouldn't be much fun. You'll use an `erlang`
node instead.

Start an erlang node:

````shell
    ~ erl -name bar@127.0.0.1
    Erlang/OTP 18 [erts-7.1] [source] [64-bit] [smp:4:4] [async-threads:10] [hipe] [kernel-poll:false] [dtrace]

    Eshell V7.1  (abort with ^G)
    (bar@127.0.0.1)1> 
````

Now connect sliver to it:

````clojure
    your-spiffy-new-node.core> (p/connect foo-node {:node-name "bar@127.0.0.1"})
    2015-Nov-16 21:28:26 +0000 salad-fingers DEBUG [sliver.handshake] - SEND NAME: foo@127.0.0.1
    2015-Nov-16 21:28:26 +0000 salad-fingers DEBUG [sliver.handshake] - DECODED:  :ok
    2015-Nov-16 21:28:26 +0000 salad-fingers DEBUG [sliver.handshake] - DECODED:  {:version 5, :flag 229372, :challenge 1347765539, :name bar@127.0.0.1}
    2015-Nov-16 21:28:26 +0000 salad-fingers DEBUG [sliver.handshake] - DECODED:  :ok
    2015-Nov-16 21:28:26 +0000 salad-fingers DEBUG [sliver.node] - Registering  #borges.type.Pid{:node foo@127.0.0.1, :pid 2, :serial 0, :creation 0}  as  bar-reader
    2015-Nov-16 21:28:26 +0000 salad-fingers DEBUG [sliver.node] - foo: Reader for bar
    2015-Nov-16 21:28:26 +0000 salad-fingers DEBUG [sliver.protocol] - Looping...
    2015-Nov-16 21:28:26 +0000 salad-fingers DEBUG [sliver.node] - Registering  #borges.type.Pid{:node foo@127.0.0.1, :pid 3, :serial 0, :creation 0}  as  bar-writer
    2015-Nov-16 21:28:26 +0000 salad-fingers DEBUG [sliver.node] - foo: Writer for bar
    #sliver.node.Node{:node-name "foo", :host "127.0.0.1", :cookie "monster", :handlers [#<handler$handle_messages sliver.handler$handle_messages@3646bfed>], :state #<Atom@5ee27c71: {:shutdown-notify #{_dead-processes-reaper bar-writer}}>, :pid-tracker #<Ref@23386cb9: {:creation 0, :serial 0, :pid 4}>, :ref-tracker #<Ref@4659de48: {:creation 0, :id [0 1 1]}>, :actor-tracker #<Ref@6dddba95: {#borges.type.Pid{:node foo@127.0.0.1, :pid 3, :serial 0, :creation 0} #<ActorRef ActorRef@5a56124c{PulsarActor@ac90383[owner: fiber-10000004]}>, #borges.type.Pid{:node foo@127.0.0.1, :pid 2, :serial 0, :creation 0} #<ActorRef ActorRef@48cb69e9{PulsarActor@3784b8bc[owner: fiber-10000003]}>, #borges.type.Pid{:node foo@127.0.0.1, :pid 0, :serial 0, :creation 0} #<ActorRef ActorRef@22966555{PulsarActor@3fd6c130[owner: fiber-10000001]}>}>, :reverse-actor-tracker #<Ref@7804c48c: {#<ActorRef ActorRef@5a56124c{PulsarActor@ac90383[owner: fiber-10000004]}> #borges.type.Pid{:node foo@127.0.0.1, :pid 3, :serial 0, :creation 0}, #<ActorRef ActorRef@48cb69e9{PulsarActor@3784b8bc[owner: fiber-10000003]}> #borges.type.Pid{:node foo@127.0.0.1, :pid 2, :serial 0, :creation 0}, #<ActorRef ActorRef@22966555{PulsarActor@3fd6c130[owner: fiber-10000001]}> #borges.type.Pid{:node foo@127.0.0.1, :pid 0, :serial 0, :creation 0}}>, :actor-registry #<Atom@2c25570e: {bar-writer #borges.type.Pid{:node foo@127.0.0.1, :pid 3, :serial 0, :creation 0}, bar-reader #borges.type.Pid{:node foo@127.0.0.1, :pid 2, :serial 0, :creation 0}, _dead-processes-reaper #borges.type.Pid{:node foo@127.0.0.1, :pid 0, :serial 0, :creation 0}}>}
    your-spiffy-new-node.core> 
````

Oh dear! Yes. Quite a few `DEBUG` messages. I hope you can forgive me.

You've now connected the `foo-node` `sliver` node to an `erlang` node
`bar@127.0.0.1`.

If you did nothing else, you should see messages in the `repl` that look like:

````clojure
    2015-Nov-16 21:28:59 +0000 salad-fingers DEBUG [sliver.protocol] - :tock
    2015-Nov-16 21:28:59 +0000 salad-fingers DEBUG [sliver.protocol] - Looping...
````

When two `erlang` nodes are connected, if they don't exchange any data they
will ping each other every minute. In this case, `bar@127.0.0.1`, the `erlang`
node, is pinging your `sliver` node which replies with a pong (in `erlang`
these are referred to as `tick` and `tock`). If a node does not reply a `tick`
with a `tock`, its counterpart will assume the node is down and close the
socket.

Now that the nodes are connected, you can send messages between them. It'll be
easier if you send messages to a registered process first since this won't
require that the receiver send its own `self` and waits for a reply.

The receiver will be an `erlang` process. The easiest thing to do is to
register the shell in the `erlang` node so that `sliver` can send messages to
it:

````erlang
    (bar@127.0.0.1)1> register(shell, self()).
    true
````

After that, send it a message from the `sliver` node:

````clojure
    your-spiffy-new-node.core> (p/spawn foo-node #(p/! foo-node ['shell "bar@127.0.0.1"] 'hai))
    #borges.type.Pid{:node foo@127.0.0.1, :pid 4, :serial 0, :creation 0}
    your-spiffy-new-node.core> 2015-Nov-16 21:47:01 +0000 salad-fingers DEBUG [sliver.node] - Sending reg msg to: shell  on  bar@127.0.0.1
    2015-Nov-16 21:47:01 +0000 salad-fingers DEBUG [sliver.protocol] - SEND-REG-MESSAGE: Sent 54 bytes
    your-spiffy-new-node.core>
````

Check that the message actually arrived:

````erlang
    (bar@127.0.0.1)2> flush().
    Shell got hai
    ok
    (bar@127.0.0.1)3> 
````

Great!

*NOTE*: The astute reader will have noticed that to send a message to a remote
registered process we need to spawn a `sliver` process (a `pulsar` actor won't
be enough). This is because the specifications of the `erlang` protocol require
that the sending `pid` is present in the message that goes in the wire (not
just the message you're trying to send, but what actually gets encoded and
travels across to the remote node). Hence, this must be done inside a `sliver`
process.

Now let's try sending a message to a remote `pid`.

First let's spawn a registered `sliver` process that receives the `pid` of its
interlocutor. Once it receives it, it replies with a message. A sort of
ping/pong action:

````clojure
    your-spiffy-new-node.core> (def ping (p/spawn foo-node
                                                   #(do
                                                     (p/register foo-node 'pong (p/self foo-node))
                                                     (a/receive ['ping from]
                                                       (do (prn "Message received...replying")
                                                           (p/! foo-node from 'pong)
                                                           (prn "Finishing now."))))))
    #'your-spiffy-new-node.core/ping
    2015-Nov-16 23:55:28 +0000 salad-fingers DEBUG [sliver.node] - Registering  #borges.type.Pid{:node foo@127.0.0.1, :pid 6, :serial 0, :creation 0}  as  pong
````

Now in the `erlang` shell we send the `sliver` process `pong` a ping message
together with its `pid`:

````erlang
    (bar@127.0.0.1)12> {pong, 'foo@127.0.0.1'} ! {ping, self()}.
    {ping,<0.39.0>}
````

`sliver` receives and delivers the message accordingly:

````clojure
    2015-Nov-16 23:55:36 +0000 salad-fingers DEBUG [sliver.handler] - HANDLER: #borges.type.Pid{:node bar@127.0.0.1, :pid 39, :serial 0, :creation 3} -> pong :: [ping #borges.type.Pid{:node bar@127.0.0.1, :pid 39, :serial 0, :creation 3}]
    2015-Nov-16 23:55:36 +0000 salad-fingers DEBUG [sliver.protocol] - Looping...
    "Message received...replying"
    "Finishing now."
    2015-Nov-16 23:55:36 +0000 salad-fingers DEBUG [sliver.protocol] - SEND-MESSAGE: Sent 50 bytes
````

Now we check back in the `erlang` shell that the `sliver` process has sent the 
reply across:

```erlang
    (bar@127.0.0.1)13> flush().
    Shell got pong
    ok
```

Huzzah!

### Data you can send

Underneath `sliver` lies [borges](https://github.com/ulises/borges) the
`erlang` term encoder/decoder. To see which types can be de/encoded, please see
[this test](...).

### Want more?

As you might've guessed, since `sliver` uses `pulsar` under the hood, many of
its features are integrated:

- links: you can `link` and `spawn-link` in `sliver`. There's no support for remote linking though. Please see the [tests](...) for examples of how to do this.
- monitors: you can `monitor`, `demonitor`, and `spawn-monitor`. Again, no support for remote monitoring just now. Please see the [tests](...) for examples of how to do this.
- references: you can create references (like `erlang`) with `make-ref`.
- finding names, pids, actors: you can find all the different personalities of a process via `whereis`, `actor-for`, `name-for`, and `pid-for`

## Develop it

Please send pull requests my way. You can run all tests with `lein test`.

## License

Copyright © 2015 Ulises Cerviño Beresi

Distributed under the MIT License.
