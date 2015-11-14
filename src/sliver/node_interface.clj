(ns sliver.node-interface)

(defprotocol NodeP
  "A simple protocol for Erlang nodes."

  (connect [node other-node]
    "Connects to an Erlang node.")

  (start [node]
    "Starts the node. The node registers with epmd (not started if not
running) and starts listening for incoming connections.")

  (stop [node]
    "Stops node. Closes all connections, etc.")

  (! [node maybe-actor-or-pid message]
    "Sends a message to either a local actor, a registered remote actor, or a pid.
It abstracts over send-message and send-registered-message.")

  (pid [node]
    "Creates a new pid.")

  (monitor [node pid]
    "Monitor a process")

  (spawn-monitor [node f]
    "Spawns a function f as a process and monitors it.")

  (demonitor [node pid monitor]
    "Demonitor process")

  (link [node pid]
        [node pid1 pid2]
    "Links (self) to pid")

  (self [node]
    "Returns pid for current actor")

  (spawn [node f] [node f opts]
    "Spawns function f as a process passing opts to pulsar's spawn.")

  (spawn-link [node f] [node f opts]
    "Spawns function f as a process passing opts to pulsar's spawn, and links to it.")

  (register [node pid name]
    "Registers a process under a name")

  (unregister [node name]
    "Unregisters a process under name")

  (whereis [node name]
    "Finds an actor's pid based on its name")

  (make-ref [node pid]
    "Creates a new reference.")

  ;; all fns below are supposed to be private
  ;; perhaps having a protocol isn't the way to go, but rather
  ;; should have a bunch of fns (private and public) and be done with it?
  ;; After all, we don't need several node implementations.

  (send-message [node pid message]
    "Sends a message to the process pid@host.")

  (send-registered-message [node from-pid process-name other-node message]
    "Sends a registered message to the process process-name@other-node.")

  (track-pid [node pid actor]
    "Keeps pids connected to actors")

  (untrack [node pid]
    "Stops tracking the pid-actor relationship")

  (actor-for [node pid]
    "Returns the actor linked to pid")

  (pid-for [node actor]
    "Returns the pid linked to actor")

  (name-for [node pid]
    "Returns the name associated to the pid")

  (get-writer [node other-node]
    "Gets the writer handling the socket connected to other-node")

  (handle-connection [node connection other-node]
    "Handles a connection after the handshake has been successful"))
