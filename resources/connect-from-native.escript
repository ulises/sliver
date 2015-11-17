#!/usr/bin/env escript
%% -*- erlang -*-
%%! -name foo@127.0.0.1 -setcookie monster

main(_) ->
    erlang:display("Connecting to bar@..."),
    true = net_kernel:connect_node('bar@127.0.0.1'),
    timer:sleep(1000).
